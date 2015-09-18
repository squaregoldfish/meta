$(function () {
	var stationsPromise = fetchStations();

	stationsPromise
		.done(function(result){
			init(parseStationsJson(result));
		})
		.fail(function(request){
			console.log(request);
		});
});

function init(stations){
	var availableTags = ["ActionScript","AppleScript","Asp","BASIC","C","C++","Clojure","COBOL","ColdFusion","Erlang","Fortran","Groovy","Haskell","Java","JavaScript","Lisp","Perl","PHP","Python","Ruby","Scala","Scheme"	];

	$('#stationsTable').DataTable( {
		data: stations.rows,
		columns: stations.columns,
		columnDefs: [
			{
				//Hide the id column
				targets: [0],
				visible: false,
				searchable: false
			}
		],
		//stateSave: true,
		lengthMenu: [[25, 50, 100, -1], [25, 50, 100, "All"]],
		//scrollX: true,

		initComplete: function () {

			this.api().columns().every( function (ind) {
				var column = this;

				var $headerControl = $('<div class="input-group">' +
					'<input class="suggestInput form-control" type="search" title="" />' +
					'<span class="input-group-btn">' +
					'<button type="button" class="btn btn-default">' +
					'<span class="glyphicon glyphicon-remove"></span>' +
					'</button></span></div>');
				var $suggestInput = $headerControl.find("input");
				var $suggestBtn = $headerControl.find("button");

				$headerControl.appendTo( $(column.header()).empty() );

				$suggestInput
					.on( 'click', function (event) {
						event.stopPropagation();

						if ($(this).val() == column.context[0].aoColumns[ind].title){
							$(this).val("");
							$(this).prop("title", "");
						}
					})
					.on( 'keyup', function (event) {
						var val = $.fn.dataTable.util.escapeRegex($(this).val());
						$(this).prop("title", val);
						column.search( val ? val : '', true, false).draw();
					})
					.on( 'blur', function (event) {
						if ($(this).val() == ""){
							$(this).val(column.context[0].aoColumns[ind].title);
						}
					})
					.autocomplete({
						source: extractSuggestions(column.data())
					});

				$suggestInput.val(column.context[0].aoColumns[ind].title);

				$suggestBtn
					.on( 'click', function (event, $suggestInput) {
						event.stopPropagation();

						var $input = $($(this).parent().siblings()[0]);

						$input.val("");
						column.search('').draw();
						$input.val(column.context[0].aoColumns[ind].title);
						$input.prop("title", "");
					})
					.prop("title", column.context[0].aoColumns[ind].title);

			});

		}
	});

}

function extractSuggestions(data){
	var aSuggestion = [];
	data.unique().sort().each( function ( d, j ) {
		aSuggestion.push(d);
	});

	return aSuggestion;
}

function fetchStations(){
	var query = [
		'PREFIX cpst: <http://meta.icos-cp.eu/ontologies/stationentry/>',
		'SELECT',
		'?id',
		'?Theme',
		'?Country',
		'?Short_name',
		'?Long_name',
		'(GROUP_CONCAT(?PI_name; separator=";") AS ?PI_names)',
		'(GROUP_CONCAT(?PI_mails; separator=";") AS ?PI_mails)',
		'?Site_type',
		'?Elevation_AS',
		'?Elevation_AG',
		'?Station_class',
		'FROM <http://meta.icos-cp.eu/ontologies/stationentry/>',
		'WHERE {',
		'?s a ?class ',
		'BIND (str(?s) AS ?id)',
		'BIND (REPLACE(str(?class),"http://meta.icos-cp.eu/ontologies/stationentry/", "") AS ?Theme)',
		'?s cpst:hasCountry ?country ',
		'BIND (str(?country) AS ?Country)',
		'?s cpst:hasShortName ?sName ',
		'BIND (str(?sName) AS ?Short_name)',
		'?s cpst:hasLongName ?lName ',
		'BIND (str(?lName) AS ?Long_name)',
		'?s cpst:hasPi ?pi ',
		'OPTIONAL{?pi cpst:hasFirstName ?piFname }',
		'?pi cpst:hasLastName ?piLname ',
		'BIND (IF(bound(?piFname), concat(?piFname, " ", ?piLname), ?piLname) AS ?PI_name)',
		'?pi cpst:hasEmail ?pIMail ',
		'BIND (str(?pIMail) AS ?PI_mails)',
		'?s cpst:hasSiteType ?siteType ',
		'BIND (str(?siteType) AS ?Site_type)',
		'OPTIONAL{?s cpst:hasElevationAboveSea ?elevationAboveSea }',
		'BIND (IF(bound(?elevationAboveSea), str(?elevationAboveSea), "?") AS ?Elevation_AS)',
		'OPTIONAL{?s cpst:hasElevationAboveGround ?elevationAboveGround }',
		'BIND (IF(bound(?elevationAboveGround), str(?elevationAboveGround), "?") AS ?Elevation_AG)',
		'?s cpst:hasStationClass ?stationClass ',
		'BIND (str(?stationClass) AS ?Station_class)',
		'}',
		'GROUP BY ?id ?Theme ?Country ?Short_name ?Long_name ?Site_type ?Elevation_AS ?Elevation_AG ?Station_class'
	].join("\n");

	var query = encodeURIComponent(query);

	return $.ajax({
		type: "GET",
		url: "https://meta.icos-cp.eu/sparql?query=" + query,
		//url: "http://127.0.0.1:9094/sparql?query=" + query,
		dataType: "json"
	});
}

function parseStationsJson(stationsJson){
	var themeName = {"AS": "Atmosphere", "ES": "Ecosystem", "OS": "OCEAN"};
	var countries = {"AD":"Andorra","AE":"United Arab Emirates","AF":"Afghanistan","AG":"Antigua and Barbuda","AI":"Anguilla","AL":"Albania","AM":"Armenia","AO":"Angola","AQ":"Antarctica","AR":"Argentina","AS":"American Samoa","AT":"Austria","AU":"Australia","AW":"Aruba","AX":"Åland Islands","AZ":"Azerbaijan","BA":"Bosnia and Herzegovina","BB":"Barbados","BD":"Bangladesh","BE":"Belgium","BF":"Burkina Faso","BG":"Bulgaria","BH":"Bahrain","BI":"Burundi","BJ":"Benin","BL":"Saint Barthélemy","BM":"Bermuda","BN":"Brunei Darussalam","BO":"Bolivia, Plurinational State of","BQ":"Bonaire, Sint Eustatius and Saba","BR":"Brazil","BS":"Bahamas","BT":"Bhutan","BV":"Bouvet Island","BW":"Botswana","BY":"Belarus","BZ":"Belize","CA":"Canada","CC":"Cocos (Keeling) Islands","CD":"Congo, the Democratic Republic of the","CF":"Central African Republic","CG":"Congo","CH":"Switzerland","CI":"Côte d'Ivoire","CK":"Cook Islands","CL":"Chile","CM":"Cameroon","CN":"China","CO":"Colombia","CR":"Costa Rica","CU":"Cuba","CV":"Cabo Verde","CW":"Curaçao","CX":"Christmas Island","CY":"Cyprus","CZ":"Czech Republic","DE":"Germany","DJ":"Djibouti","DK":"Denmark","DM":"Dominica","DO":"Dominican Republic","DZ":"Algeria","EC":"Ecuador","EE":"Estonia","EG":"Egypt","EH":"Western Sahara","ER":"Eritrea","ES":"Spain","ET":"Ethiopia","FI":"Finland","FJ":"Fiji","FK":"Falkland Islands (Malvinas)","FM":"Micronesia, Federated States of","FO":"Faroe Islands","FR":"France","GA":"Gabon","GB":"United Kingdom of Great Britain and Northern Ireland","GD":"Grenada","GE":"Georgia","GF":"French Guiana","GG":"Guernsey","GH":"Ghana","GI":"Gibraltar","GL":"Greenland","GM":"Gambia","GN":"Guinea","GP":"Guadeloupe","GQ":"Equatorial Guinea","GR":"Greece","GS":"South Georgia and the South Sandwich Islands","GT":"Guatemala","GU":"Guam","GW":"Guinea-Bissau","GY":"Guyana","HK":"Hong Kong","HM":"Heard Island and McDonald Islands","HN":"Honduras","HR":"Croatia","HT":"Haiti","HU":"Hungary","ID":"Indonesia","IE":"Ireland","IL":"Israel","IM":"Isle of Man","IN":"India","IO":"British Indian Ocean Territory","IQ":"Iraq","IR":"Iran, Islamic Republic of","IS":"Iceland","IT":"Italy","JE":"Jersey","JM":"Jamaica","JO":"Jordan","JP":"Japan","KE":"Kenya","KG":"Kyrgyzstan","KH":"Cambodia","KI":"Kiribati","KM":"Comoros","KN":"Saint Kitts and Nevis","KP":"Korea, Democratic People's Republic of","KR":"Korea, Republic of","KW":"Kuwait","KY":"Cayman Islands","KZ":"Kazakhstan","LA":"Lao People's Democratic Republic","LB":"Lebanon","LC":"Saint Lucia","LI":"Liechtenstein","LK":"Sri Lanka","LR":"Liberia","LS":"Lesotho","LT":"Lithuania","LU":"Luxembourg","LV":"Latvia","LY":"Libya","MA":"Morocco","MC":"Monaco","MD":"Moldova, Republic of","ME":"Montenegro","MF":"Saint Martin (French part)","MG":"Madagascar","MH":"Marshall Islands","MK":"Macedonia, the former Yugoslav Republic of","ML":"Mali","MM":"Myanmar","MN":"Mongolia","MO":"Macao","MP":"Northern Mariana Islands","MQ":"Martinique","MR":"Mauritania","MS":"Montserrat","MT":"Malta","MU":"Mauritius","MV":"Maldives","MW":"Malawi","MX":"Mexico","MY":"Malaysia","MZ":"Mozambique","NA":"Namibia","NC":"New Caledonia","NE":"Niger","NF":"Norfolk Island","NG":"Nigeria","NI":"Nicaragua","NL":"Netherlands","NO":"Norway","NP":"Nepal","NR":"Nauru","NU":"Niue","NZ":"New Zealand","OM":"Oman","PA":"Panama","PE":"Peru","PF":"French Polynesia","PG":"Papua New Guinea","PH":"Philippines","PK":"Pakistan","PL":"Poland","PM":"Saint Pierre and Miquelon","PN":"Pitcairn","PR":"Puerto Rico","PS":"Palestine, State of","PT":"Portugal","PW":"Palau","PY":"Paraguay","QA":"Qatar","RE":"Réunion","RO":"Romania","RS":"Serbia","RU":"Russian Federation","RW":"Rwanda","SA":"Saudi Arabia","SB":"Solomon Islands","SC":"Seychelles","SD":"Sudan","SE":"Sweden","SG":"Singapore","SH":"Saint Helena, Ascension and Tristan da Cunha","SI":"Slovenia","SJ":"Svalbard and Jan Mayen","SK":"Slovakia","SL":"Sierra Leone","SM":"San Marino","SN":"Senegal","SO":"Somalia","SR":"Suriname","SS":"South Sudan","ST":"Sao Tome and Principe","SV":"El Salvador","SX":"Sint Maarten (Dutch part)","SY":"Syrian Arab Republic","SZ":"Swaziland","TC":"Turks and Caicos Islands","TD":"Chad","TF":"French Southern Territories","TG":"Togo","TH":"Thailand","TJ":"Tajikistan","TK":"Tokelau","TL":"Timor-Leste","TM":"Turkmenistan","TN":"Tunisia","TO":"Tonga","TR":"Turkey","TT":"Trinidad and Tobago","TV":"Tuvalu","TW":"Taiwan, Province of China","TZ":"Tanzania, United Republic of","UA":"Ukraine","UG":"Uganda","UM":"United States Minor Outlying Islands","US":"United States of America","UY":"Uruguay","UZ":"Uzbekistan","VA":"Holy See","VC":"Saint Vincent and the Grenadines","VE":"Venezuela, Bolivarian Republic of","VG":"Virgin Islands, British","VI":"Virgin Islands, U.S.","VN":"Viet Nam","VU":"Vanuatu","WF":"Wallis and Futuna","WS":"Samoa","YE":"Yemen","YT":"Mayotte","ZA":"South Africa","ZM":"Zambia","ZW":"Zimbabwe"};

	var columns = stationsJson.head.vars.map(function (currVal){
		var cols = {};
		cols.title = currVal;
		return cols;
	});

	var rows = stationsJson.results.bindings.map(function (currObj){
		var row = [];

		columns.forEach(function(colObj){
			if (colObj.title == "Theme"){
				row.push(themeName[currObj[colObj.title].value]);
			} else if (colObj.title == "Country"){
				row.push(countries[currObj[colObj.title].value] + " (" + currObj[colObj.title].value + ")");
			} else if (colObj.title == "PI_names" || colObj.title == "PI_mails"){
				row.push(currObj[colObj.title].value.replace(";", "<br>"));
			} else {
				row.push(currObj[colObj.title].value);
			}
		});

		return row;
	});

	columns.forEach(function(colObj){
		colObj.title = colObj.title.replace("_", " ");
	});

	var stations = {};
	stations.rows = rows;
	stations.columns = columns;

	return stations;
}