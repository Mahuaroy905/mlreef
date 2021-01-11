package com.mlreef.rest.feature.marketplace

import com.mlreef.rest.AccessLevel
import com.mlreef.rest.CodeProject
import com.mlreef.rest.CodeProjectRepository
import com.mlreef.rest.DataProcessor
import com.mlreef.rest.DataProcessorType
import com.mlreef.rest.DataProject
import com.mlreef.rest.DataProjectRepository
import com.mlreef.rest.Person
import com.mlreef.rest.ProcessorVersion
import com.mlreef.rest.Project
import com.mlreef.rest.ProjectRepository
import com.mlreef.rest.ProjectType
import com.mlreef.rest.SearchableTagRepository
import com.mlreef.rest.Subject
import com.mlreef.rest.VisibilityScope
import com.mlreef.rest.api.v1.SearchRequest
import com.mlreef.rest.exceptions.ErrorCode
import com.mlreef.rest.exceptions.NotFoundException
import com.mlreef.rest.external_api.gitlab.TokenDetails
import com.mlreef.rest.marketplace.Searchable
import com.mlreef.rest.marketplace.SearchableTag
import com.mlreef.rest.marketplace.SearchableType
import com.mlreef.rest.utils.QueryBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.lang.System.currentTimeMillis
import java.util.UUID
import javax.persistence.EntityManager

/**
 * Handles the creation and linking of Searchables with their Marketplace Entries
 * All Operations which are not only scoped into a single Entry are handled by this Service
 */
@Service
class MarketplaceService(
    private val projectRepository: ProjectRepository,
    private val dataProjectRepository: DataProjectRepository,
    private val codeProjectRepository: CodeProjectRepository,
    private val searchableTagRepository: SearchableTagRepository,
    private val entityManager: EntityManager,
) {
    val log = LoggerFactory.getLogger(this::class.java) as Logger

    fun searchProjects(request: SearchRequest, pageable: Pageable, token: TokenDetails?): Page<out Project> {
        var returnEmptyResult = false

        val finalProjectType = request.projectType
            ?: if (request.searchableType == SearchableType.DATA_PROJECT) {
                ProjectType.DATA_PROJECT
            } else if (request.searchableType == SearchableType.CODE_PROJECT) {
                ProjectType.CODE_PROJECT
            } else null

        val builder = getQueryBuilderForType(finalProjectType)

        val finalVisibility = if (token == null || token.isVisitor) {
            VisibilityScope.PUBLIC
        } else {
            request.visibility
        }

        val finalProcessorType = request.processorType
            ?: if (request.searchableType == SearchableType.OPERATION) {
                DataProcessorType.OPERATION
            } else if (request.searchableType == SearchableType.ALGORITHM) {
                DataProcessorType.ALGORITHM
            } else if (request.searchableType == SearchableType.VISUALIZATION) {
                DataProcessorType.VISUALIZATION
            } else null

        if (finalVisibility == VisibilityScope.PRIVATE) {
            builder
                .and()
                .openBracket()
                .equals("visibilityScope", VisibilityScope.PRIVATE)
                .and()
                .`in`("id", token?.projects?.map { it.key } ?: listOf())
                .closeBracket()
        } else if (finalVisibility == null) {
            builder
                .and()
                .openBracket()
                .openBracket()
                .equals("visibilityScope", VisibilityScope.PRIVATE)
                .and()
                .`in`("id", token?.projects?.map { it.key } ?: listOf())
                .closeBracket()
                .or()
                .equals("visibilityScope", VisibilityScope.PUBLIC)
                .closeBracket()
        } else {
            builder.and().equals("visibilityScope", VisibilityScope.PUBLIC)
        }

        request.globalSlug?.let { builder.and().like("globalSlug", "%$it%", caseSensitive = false) }
        request.globalSlugExact?.let { builder.and().equals("globalSlug", it, caseSensitive = false) }
        request.slug?.let { builder.and().like("slug", "%$it%", caseSensitive = false) }
        request.slugExact?.let { builder.and().equals("slug", it, caseSensitive = false) }
        request.maxStars?.let { builder.and().lessOrEqualThan("_starsCount", it) }
        request.minStars?.let { builder.and().greaterOrEqualThan("_starsCount", it) }
        finalProcessorType?.let { builder.and().equals("type", it, "processor") }
        request.inputDataTypes?.let { builder.and().containsAll("inputDataTypes", it) }
        request.outputDataTypes?.let { builder.and().containsAll("outputDataTypes", it) }
        request.inputDataTypesOr?.let { builder.and().containsAny("inputDataTypes", it) }
        request.outputDataTypesOr?.let { builder.and().containsAny("outputDataTypes", it) }
        request.tags?.let {
            if (it.size > 0) {
                val searchableTags = searchableTagRepository.findAllByNameIsInIgnoreCase(it)
                if (searchableTags.size == it.size) {
                    builder.and().containsAll("tags", searchableTags)
                } else {
                    returnEmptyResult = true
                }
            }
        }
        request.tagsOr?.let {
            if (it.size > 0) {
                val searchableTags = searchableTagRepository.findAllByNameIsInIgnoreCase(it)
                if (searchableTags.size == 0) {
                    returnEmptyResult = true
                } else {
                    builder.and().containsAny("tags", searchableTags)
                }
            }
        }
        request.minForksCount?.let { builder.and().greaterOrEqualThan("forksCount", it) }
        request.maxForksCount?.let { builder.and().lessOrEqualThan("forksCount", it) }
        request.modelTypeOr?.let { builder.and().`in`("modelType", it, "version", caseSensitive = false) }
        request.mlCategoryOr?.let { builder.and().`in`("mlCategory", it, "version", caseSensitive = false) }
        request.ownerIdsOr?.let { builder.and().`in`("ownerId", it) }
        request.name?.let { builder.and().like("name", "%${it}%", caseSensitive = false) }
        request.nameExact?.let { builder.and().equals("name", it, caseSensitive = false) }
        request.namespace?.let { builder.and().like("gitlabNamespace", "%${it}%", caseSensitive = false) }
        request.namespaceExact?.let { builder.and().equals("gitlabNamespace", it, caseSensitive = false) }
        request.published?.let {
            if (it) {
                builder.and().isNotNull("publishingInfo.finishedAt", "version")
            } else {
                builder.and().isNull("publishingInfo.finishedAt", "version")
            }
        }

        return if (returnEmptyResult) {
            PageImpl(listOf(), pageable, 0)
        } else {
            builder.select(pageable, true) //use distinct because with connect ManyToMany SearchableTags table
        }
    }

    private fun getQueryBuilderForType(type: ProjectType?): QueryBuilder<out Project> {
        val result = when (type) {
            ProjectType.CODE_PROJECT -> QueryBuilder(entityManager, CodeProject::class.java)
            ProjectType.DATA_PROJECT -> QueryBuilder(entityManager, DataProject::class.java)
            else -> QueryBuilder(entityManager, Project::class.java)
        }

        result.joinLeft<DataProcessor>("dataProcessor", alias = "processor")
        result.joinLeft<ProcessorVersion>("processorVersion", "processor", "version")

        return result
    }

    /**
     * Creates a new Entry for a Searchable with a Subject as owner/author.
     * Searchable can be a DataProject or a DataProcessorr
     */
    @Transactional
    fun prepareEntry(searchable: Searchable, owner: Subject): Project =
        when (searchable) {
            is DataProject -> searchable.copy(
                name = searchable.name,
                description = searchable.description,
                visibilityScope = searchable.visibilityScope,
                globalSlug = "data-project-${searchable.slug}"
            ) as DataProject
            is CodeProject -> searchable.copy(
                name = searchable.name,
                description = searchable.description,
                visibilityScope = searchable.visibilityScope,
                globalSlug = "code-project-${searchable.slug}"
            ) as CodeProject
            else -> throw NotImplementedError("Searchable does not support that type")
        }
            .also { log.info("Create new Searchable for searchable id ${searchable.getId()} and owner $owner") }
            .let { save(it) }

    /**
     * Creates a new Entry for a Searchable with a Subject as owner/author.
     * Searchable can be a DataProject or a DataProcessorr
     *
     */
    fun assertEntry(searchable: Searchable, owner: Subject): Project {
        val existing = projectRepository.findByIdOrNull(searchable.getId())
        existing?.let { return@let existing }
        return prepareEntry(searchable, owner)
    }

    @Transactional
    fun save(searchable: Searchable): Project =
        when (searchable) {
            is DataProject -> dataProjectRepository.save(searchable)
            is CodeProject -> codeProjectRepository.save(searchable)
            is Project -> projectRepository.save(searchable)
            else -> throw IllegalStateException("Not possible ")
        }

    /**
     * Adds a star from one Person
     */
    fun addStar(searchable: Project, person: Person): Project =
        searchable.addStar(person)
            .let { save(it) }

    /**
     * Adds a preexisting Tag to this Entry, Tags are stored just once per Entry
     */
    @Transactional
    fun addTags(searchable: Project, tags: List<SearchableTag>): Project =
        searchable.addTags(tags)
            .let { save(it) }

    /**
     * Sets the Tags of this Entry, similar to removing all Tags and adding new ones
     */
    fun defineTags(searchable: Project, tags: List<SearchableTag>): Project =
        searchable.clone(tags = hashSetOf())
            .addTags(tags)
            .let { save(it) }

    fun removeStar(searchable: Project, person: Person): Project =
        searchable.removeStar(person)
            .also { log.info("User $person put removed star from $Searchable") }
            .let { save(it) }

    fun findEntriesForProjects(pageable: Pageable, projectsMap: Map<UUID, AccessLevel?>): List<Project> {
        val ids: List<UUID> = projectsMap.filterValues { AccessLevel.isSufficientFor(it, AccessLevel.GUEST) }.map { it.key }.toList()
        val projects = projectRepository.findAccessibleProjects(ids, pageable)
        return projectRepository.findAllByVisibilityScope(VisibilityScope.PUBLIC, pageable)
            .toMutableSet()
            .apply { addAll(projects) }
            .toList()
    }

    fun findEntriesForProjectsBySlug(projectsMap: Map<UUID, AccessLevel?>, slug: String): Project {
        val ids: List<UUID> = projectsMap.filterValues { AccessLevel.isSufficientFor(it, AccessLevel.GUEST) }.map { it.key }.toList()
        return projectRepository.findByGlobalSlugAndVisibilityScope(slug, VisibilityScope.PUBLIC)
            ?: projectRepository.findAccessibleProject(ids, slug)
            ?: throw NotFoundException(ErrorCode.ProjectNotExisting, "Not found")
    }

    /**
     * This method must perform the search in a complex way:
     * - Paging is supported
     * - Filtering is possible
     * - Ordering is supported by Spring Paging support (except rank)
     * - If a searchQuery is applied, the result is sorted by rank DESC (scoped in the current page)
     * - If no full text search is applied, the SearchResult will have "1.0" as idempotent rank
     *
     * Due to performance reasons, and limited development resources, text search ranking is just scoped to the current page!
     * So currently we cannot order per rank and afterwards apply paging, but just offer normal paging and sorting, and then sort the page results per rank.
     *
     * *Hint*: If you need a "global order by rank" just use a page size of over 9000 which should result in one page which is ordered per rank
     */
    fun performSearchByText(pageable: Pageable, query: String, queryAnd: Boolean, token: TokenDetails?): Collection<SearchResult> {
        val time = currentTimeMillis()
        val builder = getQueryBuilderForType(null)

        builder
            .and()
            .openBracket()
            .equals("visibilityScope", VisibilityScope.PUBLIC)
            .apply {
                if (token != null && token.projects.size > 0) {
                    this
                        .or()
                        .openBracket()
                        .equals("visibilityScope", VisibilityScope.PRIVATE)
                        .and()
                        .`in`("id", token.projects.map { it.key })
                        .closeBracket()
                }
            }
            .closeBracket()

        val accessibleEntriesMap = builder.select(true).associateBy { it.id }

        val ftsQueryPart = buildFTSCondition(query, queryAnd = queryAnd ?: true)

        /**
         * Requires the "update_fts_document" PSQL TRIGGER and "project_fts_index" gin index
         * Currently Fulltext search is implemented via psql and _relies_ on that, be aware of that when you change DB!
         */

        /**
         * Requires the "update_fts_document" PSQL TRIGGER and "project_fts_index" gin index
         * Currently Fulltext search is implemented via psql and _relies_ on that, be aware of that when you change DB!
         */

        val fulltextSearch = projectRepository
            .fulltextSearch(ftsQueryPart, accessibleEntriesMap.keys)
        val rankedResults = fulltextSearch
            .map { UUIDRank(UUID.fromString(it.id), it.rank) }

        log.debug("Found ${rankedResults.size} fulltext search results with query ranking")

        // step 1: reduce all ranks to ranks with Id in current page
        val filteredRanks = rankedResults.filter { it.id in accessibleEntriesMap }

        // step 2: order baselist with currentRanks probability
        val finalPageRankedEntries = filteredRanks.mapNotNull {
            accessibleEntriesMap[it.id]
        }
        val searchResults = finalPageRankedEntries.zip(rankedResults).map {
            SearchResult(it.first, SearchResultProperties(rank = it.second.rank.toFloat()))
        }
        log.info("Marketplace fulltext search found ${searchResults.size} fts results within ${currentTimeMillis() - time} ms")
        return searchResults
    }

    private fun findTagsForStrings(tagNames: List<String>): List<SearchableTag> =
        searchableTagRepository.findAllByPublicTrueAndNameIsIn(tagNames.map(String::toLowerCase))

    /**
     * Requires the "update_fts_document" PSQL TRIGGER and "project_fts_index" gin index
     *
     * Currently Fulltext search is implemented via psql and _relies_ on that, be aware of that when you change DB!
     */
    private fun buildFTSCondition(query: String, queryAnd: Boolean): String {
        val list = query.replace("\"", "")
            .replace("\'", "").split(" ")
        return if (queryAnd) {
            list.joinToString(" & ")
        } else {
            list.joinToString(" | ")
        }
    }
}

data class SearchResultProperties(
    val rank: Float,
)

data class SearchResult(
    val project: Project,
    val properties: SearchResultProperties?,
)

data class UUIDRank(
    val id: UUID,
    val rank: Double,
)
