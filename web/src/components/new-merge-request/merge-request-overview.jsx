import React, { Component } from 'react';
import { CircularProgress } from '@material-ui/core';
import { connect } from 'react-redux';
import {
  number, shape, string, arrayOf,
} from 'prop-types';
import Navbar from '../navbar/navbar';
import ProjectContainer from '../projectContainer';
import './merge-request-overview.css';
import CustomizedButton from '../CustomizedButton';
import { mrStates } from '../../dataTypes';
import { getTimeCreatedAgo } from '../../functions/dataParserHelpers';
import BlackBorderedButton from '../BlackBorderedButton';

/**
   * @param {mrs} is the merge requests list to be classified by state
   */
const classifyMrsByState = (mrs) => mrStates.map((state) => {
  const list = mrs.filter((mergeReq) => mergeReq.state === state);
  const { length } = list;
  return {
    mrState: state,
    list,
    length,
  };
});

class MergeRequestOverview extends Component {
  constructor(props) {
    super(props);
    this.state = {
      isFetching: true,
      mrsList: [],
      btnSelected: 'all-btn',
    };
    this.handleFilterBtnClick = this.handleFilterBtnClick.bind(this);
  }
  
  componentDidMount = () => {
    // spinner for 2 seconds and then the results
    setTimeout(() => {
      this.setState({ isFetching: false });
    }, 2000);
  }

  static getDerivedStateFromProps(nextProps, prevState) {
    if (nextProps.mergeRequests.length > 0) {
      const classifiedMrs = classifyMrsByState(nextProps.mergeRequests);
      return { mrsList: classifiedMrs };
    }

    return prevState;
  }

  handleFilterBtnClick = (e) => {
    this.setState({ btnSelected: e.currentTarget.id });
  }

  filterStateAndCount = (stateIndex) => {
    const {
      mrsList,
    } = this.state;
    return mrsList
      .filter(
        (mrClass) => mrClass.mrState === mrStates[stateIndex],
      )[0] || { list: [] };
  }

  render() {
    const {
      isFetching,
      mrsList,
      btnSelected,
    } = this.state;
    const {
      selectedProject,
      history,
    } = this.props;
    const openedMrs = this.filterStateAndCount(0);
    const closedMrs = this.filterStateAndCount(1);
    const mergedMrs = this.filterStateAndCount(2);

    const projectName = selectedProject.name;
    const groupName = selectedProject.namespace.name;
    return (
      <>
        <Navbar />
        <ProjectContainer
          project={selectedProject}
          activeFeature="data"
          folders={[groupName, projectName, 'Data']}
        />
        <div className="main-content">
          {isFetching
            ? <div id="circular-progress-container"><CircularProgress /></div>
            : (
              <>
                <br />
                <br />
                <div id="filter-buttons-new-mr">
                  <div id="filter-btns">
                    <BlackBorderedButton id="open-btn" textContent={`${openedMrs.list.length} Open`} onClickHandler={this.handleFilterBtnClick} />
                    <BlackBorderedButton id="merged-btn" textContent={`${mergedMrs.list.length} Merged`} onClickHandler={this.handleFilterBtnClick} />
                    <BlackBorderedButton id="closed-btn" textContent={`${closedMrs.list.length} Closed`} onClickHandler={this.handleFilterBtnClick} />
                    <BlackBorderedButton id="all-btn" textContent="All" onClickHandler={this.handleFilterBtnClick} />
                  </div>
                  <div>
                    <CustomizedButton
                      id="new-mr-link"
                      loading={false}
                      onClickHandler={() => {
                        history.push(`/my-projects/${selectedProject.id}/master/new-merge-request`);
                      }}
                      buttonLabel="New merge request"
                    />
                  </div>
                </div>
                <div id="merge-requests-container-div">
                  {btnSelected === 'open-btn' && openedMrs.list.length > 0
                    ? <MergeRequestCard mergeRequestsList={openedMrs} key={openedMrs.mrState} />
                    : null}
                  {btnSelected === 'merged-btn' && mergedMrs.list.length > 0
                    ? <MergeRequestCard mergeRequestsList={mergedMrs} key={mergedMrs.mrState} />
                    : null}
                  {btnSelected === 'closed-btn' && closedMrs.list.length > 0
                    ? <MergeRequestCard mergeRequestsList={closedMrs} key={closedMrs.mrState} />
                    : null}
                  {btnSelected === 'all-btn' && mrsList.length > 0
                    ? mrsList.map((mrsClass) => (
                      <MergeRequestCard mergeRequestsList={mrsClass} key={mrsClass.mrState} />
                    ))
                    : null}
                </div>
                <br />
                <br />
              </>
            )}
        </div>
      </>
    );
  }
}

const MergeRequestCard = ({ mergeRequestsList }) => (
  <div className="merge-request-card" key={mergeRequestsList.mrState}>
    <div className="title">
      <p>{mergeRequestsList.mrState}</p>
    </div>
    <div>
      {mergeRequestsList.list.map(((mr, index) => (
        <div className="merge-request-subcard" key={`${index.toString()}`}>
          <p><b>{mr.title}</b></p>
          <p>
            {mr.reference}
            {' '}
            Opened by
            {' '}
            <b>{mr.author.username}</b>
            {' '}
            {getTimeCreatedAgo(mr.updated_at, new Date())}
            {' '}
            ago
          </p>
        </div>
      )
      ))}
    </div>
  </div>
);

MergeRequestCard.propTypes = {
  mergeRequestsList: shape({
    mrState: string.isRequired,
    list: arrayOf(shape({
      title: string.isRequired,
      reference: string.isRequired,
      username: string,
      updated_at: string.isRequired,
    })).isRequired,
  }).isRequired,
};

function mapStateToProps(state) {
  return {
    selectedProject: state.projects.selectedProject,
    mergeRequests: state.mergeRequests,
  };
}

MergeRequestOverview.propTypes = {
  selectedProject: shape({
    id: number.isRequired,
    name: string.isRequired,
    namespace: shape({
      name: string.isRequired,
    }).isRequired,
  }).isRequired,
};

export default connect(mapStateToProps)(MergeRequestOverview);
