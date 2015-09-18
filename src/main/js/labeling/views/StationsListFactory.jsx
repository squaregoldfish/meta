module.exports = function(stationListStore, chooseStationAction){

	return React.createClass({

		mixins: [Reflux.connect(stationListStore)],

		render: function(){

			return <div className="list-group">{

				this.state.stations.map(function(station){

					var clickHandler = () => chooseStationAction(station);

					return <li
						className={"list-group-item list-group-item-" + (station.chosen ? "info" : "default")}
						key={station.uri}
						onClick={clickHandler}>
						{[station.longName, ' (', station.theme, ')'].join('')}
					</li>;
				})

			}</div>;
		}

	});
}
