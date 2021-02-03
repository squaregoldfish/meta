import {themeGlyphs} from '../configs.js';
import {status} from '../models/ApplicationStatus.js';

function panelClass(station){
	var statusString = `${!!station.chosen} ${!!station.isUsersStation} ${!!station.isUsersNeedingActionStation}`;
	switch (statusString){
		case 'false false false': return 'default';
		case 'false false true': return 'warning';
		case 'false true false': return 'info';
		case 'false true true': return 'warning';
		case 'true false false': return 'default';
		case 'true false true': return 'danger';
		case 'true true false': return 'primary';
		case 'true true true': return 'danger';
	}
}

function statusClassShort(appStatus){
	if(!appStatus) return "default";
	switch(appStatus){
		case status.neverSubmitted: return "default";
		case status.notSubmitted: return "info";
		case status.submitted: return "warning";
		case status.acknowledged: return "primary";
		case status.approved: return "success";
		case status.rejected: return "danger";
		case status.step2ontrack: return "success";
		case status.step2approved: return "success";
		case status.step2stalled: return "danger";
		case status.step2delayed: return "warning";
		case status.step3approved: return "success";
		default: return "danger";
	}
}

export function statusClass(appStatus){
	return "label label-" + statusClassShort(appStatus);
}

export function statusLabel(appStatus){
	const initialLabel = "Awaiting Step 1";
	if(!appStatus) return initialLabel;

	switch(appStatus){
		case status.neverSubmitted: return initialLabel;
		case status.notSubmitted: return "Step 1 returned";
		case status.submitted: return "Step 1 submitted";
		case status.acknowledged: return "Step 1 acknowledged";
		case status.approved: return "Step 1 approved";
		case status.rejected: return "Step 1 rejected";
		case status.step2ontrack: return "Step 2 on track";
		case status.step2approved: return "Step 2 approved";
		case status.step2stalled: return "Step 2 stalled";
		case status.step2delayed: return "Step 2 delayed";
		case status.step3approved: return "Label approved";
		default: return "Invalid status: " + appStatus;
	}
}

export function statusFullText(appStatus){
	const initialText = "Step 1 application has not been submitted yet";
	if(!appStatus) return initialText;
	switch(appStatus){
		case status.neverSubmitted: return initialText;
		case status.notSubmitted: return "Step 1 application was returned for resubmission";
		case status.submitted: return "Step 1 application submitted, waiting for acknowlegment and review";
		case status.acknowledged: return "Step 1 application sumbission acknowledged, waiting for review";
		case status.approved: return "Step 1 application has been approved!";
		case status.rejected: return "Step 1 application has been rejected!";
		case status.step2ontrack: return "Step 2 of labeling is on track";
		case status.step2approved: return "Step 2 of labeling has been approved";
		case status.step2stalled: return "Step 2 of labeling has been stalled";
		case status.step2delayed: return "Step 2 of labeling has been delayed";
		case status.step3approved: return "Labeling is complete!";
		default: return `Invalid application status '${appStatus}'! Please inform Carbon Portal developers about the problem.`;
	}
}

const ShowLazily = React.createClass({

	getInitialState: function(){
		return {show: false};
	},

	componentDidMount: function(){
		var self = this;
		self.timeout = setTimeout(
			() => self.setState({show: true}),
			self.props.delay
		);
	},

	componentWillUnmount: function(){
		clearTimeout(this.timeout);
	},

	render: function(){
		return this.state.show
			? <div>{this.props.children}</div>
			: null;
	}
});

const marginStyle = { marginLeft: 5, marginRight: 5 };
const panelHeaderStyle = {
	display: 'flex',
	flexDirection: 'row',
	justifyContent: 'space-between',
	whiteSpace: 'nowrap'
};
const appStatusCommentStyle = {
	alignSelf: 'flex-start',
	flex: 10,
	marginLeft: 25,
	marginRight: 35,
	whiteSpace: 'nowrap',
	overflow: 'hidden',
	textOverflow: 'ellipsis'
};

function getAppStatusComment(status, appStatusComment) {
	return (status === 'STEP2DELAYED' || status === 'STEP2STALLED') && appStatusComment
		? appStatusComment
		: null;
}

export default function(StationAuthStore, themeToStation, chooseStationAction){

	return React.createClass({

		mixins: [Reflux.connect(StationAuthStore)],

		render: function(){

			var self = this;

			if (self.state.stations.length == 0){
				return <ShowLazily delay={100}>
					<div className="container-fluid">
						<div className="row">
							<div className="col-md-2">
								<h4>
									<span className="label label-danger">No stations match your search criteria</span>
								</h4>
							</div>
						</div>
					</div>
				</ShowLazily>;
			} else {
				return (
					<div>
						<h4 style={marginStyle}>
							<span class="label label-default">Stations count: {self.state.stations.length}</span>
						</h4>
						<ul className="list-unstyled">
							{
								_.map(self.state.stations, function (station) {

									var panelClasses = 'panel panel-' + panelClass(station);

									var icon = 'glyphicon glyphicon-' + (themeGlyphs[station.theme] || 'question-sign');

									var Station = themeToStation[station.theme];

									var applicationStatus = station.hasApplicationStatus;
									var appStatusCommentTxt = getAppStatusComment(applicationStatus, station.hasAppStatusComment);
									var applicationStatusCSS = statusClass(station.hasApplicationStatus);

									return <li key={station.stationUri} ref={station.chosen ? "chosenStation" : null}>

										<div className={panelClasses} style={marginStyle}>
											<div className="cp-lnk panel-heading" style={panelHeaderStyle} onClick={() => chooseStationAction(station)}>
												<span className={icon} style={{top: 3}} />
												<span style={{ marginLeft: 10, alignSelf: 'flex-start' }}>{station.hasLongName}</span>
												<span style={{ marginLeft: 10, alignSelf: 'flex-start' }}>(Last status update: {getLastAppUpdate(station.hasAppStatusDate)})</span>
												<span className="text-muted" style={appStatusCommentStyle}>{appStatusCommentTxt}</span>
												<label className={applicationStatusCSS}>
													<span style={{ fontWeight: 600, fontSize: 12, position: 'relative', top: 2 }}>{statusLabel(applicationStatus)}</span>
												</label>
											</div>
											{station.chosen ? <div className="panel-body"><Station stationUri={station.stationUri} stationLabel={station.hasLongName} /></div> : null}
										</div>

									</li>;
								})

							}</ul>
					</div>
					
				);
			}
		},

		componentDidUpdate: function(){
			var elem = React.findDOMNode(this.refs.chosenStation);
			if(!elem) return;
			if(elem.getBoundingClientRect().top < 0) elem.scrollIntoView();
		}
	});
}

const getLastAppUpdate = (hasAppStatusDate) => {
	return hasAppStatusDate
		? hasAppStatusDate.toLocaleString('se-SE')
		: 'unknown';
};

