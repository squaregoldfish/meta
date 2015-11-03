import {baseUri, lblUri, ontUri, filesUri, stationOwlClassToTheme, themeToProperties} from './configs.js';

const stationPisQuery = `
	PREFIX cpst: <${baseUri}>
	PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
	SELECT *
	FROM <${ontUri}>
	FROM <${baseUri}>
	FROM NAMED <${lblUri}>
	WHERE{
		?owlClass rdfs:subClassOf cpst:Station .
		?s a ?owlClass .
		?s cpst:hasPi ?pi .
		?pi cpst:hasEmail ?email .
		?s cpst:hasShortName ?provShortName .
		?s cpst:hasLongName ?provLongName .
		OPTIONAL{
			GRAPH <${lblUri}> {
				OPTIONAL {?s cpst:hasShortName ?hasShortName }
				OPTIONAL {?s cpst:hasLongName ?hasLongName }
				OPTIONAL {?s cpst:hasApplicationStatus ?hasApplicationStatus }
			}
		}
	}`;

function postProcessStationsList(stations){
	return _.values(_.groupBy(stations, 's'))
		.map(samePi => {
			var station = samePi[0];
			return _.extend(
				_.omit(station, 'email', 'pi', 'provShortName', 'provLongName', 's', 'owlClass'), {
					emails: _.pluck(samePi, 'email'),
					hasShortName: station.hasShortName || station.provShortName,
					hasLongName: station.hasLongName || station.provLongName,
					stationUri: station.s,
					theme: stationOwlClassToTheme(station.owlClass)
				}
			);
		});
}

function getStationQuery(stationUri){
	return `
		PREFIX cpst: <${baseUri}>
		SELECT *
		FROM NAMED <${lblUri}>
		FROM NAMED <${baseUri}>
		WHERE{
			GRAPH ?g {
				<${stationUri}> ?p ?o .
			}
		}`;
}

function compileStationInfo(bindings, theme){

	let props = themeToProperties(theme);
	let propUris = _.map(props, prop => baseUri + prop);
	let propLookup = _.object(propUris, props);

	function toInfo(bindings){
		let relevant = bindings.filter(binding => propLookup.hasOwnProperty(binding.p));
		let propValuePairs = _.map(
			_.groupBy(relevant, 'p'),
			(bindings, propUri) => [propLookup[propUri], bindings[0].o]
		);
		return _.object(propValuePairs);
	}

	let provisionalInfo = toInfo(bindings.filter(b => b.g === baseUri));
	let labelingInfo = toInfo(bindings.filter(b => b.g === lblUri));

	return _.extend(provisionalInfo, labelingInfo);
}

function getFilesQuery(stationUri){
	return `
		PREFIX cpst: <${baseUri}>
		PREFIX cpfls: <${filesUri}>
		SELECT DISTINCT ?file ?fileType ?fileName
		FROM <${lblUri}>
		WHERE{
			<${stationUri}> cpst:hasAssociatedFile ?file .
			?file cpfls:hasType ?fileType .
			?file cpfls:hasName ?fileName .
		}
	`;
}

export default function(ajax, sparql){

	function getStationFiles(stationUri){
		return sparql(getFilesQuery(stationUri));
	}

	function getStationLabelingInfo(stationUri, theme){

		let mainInfo = sparql(getStationQuery(stationUri))
			.then(bindings => compileStationInfo(bindings, theme));

		let filesInfo = getStationFiles(stationUri);

		return Promise.all([mainInfo, filesInfo])
			.then(all => {
				let [main, files] = all;
				return _.extend({files, stationUri, theme}, main);
			});
	}

	return {
		getStationPis: () => sparql(stationPisQuery).then(postProcessStationsList),

		getStationInfo: getStationLabelingInfo,
		saveStationInfo: info => ajax.postJson('save', info),
		updateStatus: (stationUri, newStatus) => ajax.postJson('updatestatus', {stationUri, newStatus}),

		getStationFiles: getStationFiles,
		uploadFile: formData => ajax.uploadFormData('fileupload', formData),
		deleteFile: fileInfo => ajax.postJson('filedeletion', fileInfo),

		whoAmI: () => ajax.getJson('userinfo'),
		saveUserInfo: userInfo => ajax.postJson('saveuserinfo', userInfo)
	};
};

