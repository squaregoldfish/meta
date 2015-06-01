var LiteralPropertyValues = require('./LiteralPropertyValues.js');
var ObjectPropertyValues = require('./ObjectPropertyValues.js');

function Individual(individDto){

	this._dto = individDto;
	this._propVals = this.makePropertyValues(individDto);

}

Individual.prototype.getInfo = function(){
	return this._dto.resource;
};

Individual.prototype.getClassInfo = function(){
	return this._dto.owlClass.resource;
};

Individual.prototype.getLabel = function(){
	return this.getInfo().displayName;
};

Individual.prototype.getPropertyValues = function(){
	return this._propVals;
};

Individual.prototype.getKey = function(){
	return "ind_" + this.getInfo().uri;
};

Individual.prototype.makePropertyValues = function(individDto){
	var propsToVals = _.groupBy(individDto.values, function(indVal){
		return indVal.property.uri;
	});

	var unordered = _.map(this._dto.owlClass.properties, function(prop){

		var values = propsToVals[prop.resource.uri] || [];

		switch(prop.type){
			case "dataProperty": return new LiteralPropertyValues(prop, values);
			case "objectProperty" : return new ObjectPropertyValues(prop, values);
			default: throw new Error("Unknown OWL Property type: " + prop.type);
		}

	});

	var sortAlphabetically = function(propValsGroup){
		return _.sortBy(propValsGroup, function(propVals){
			return propVals.getPropertyInfo().displayName.toLowerCase();
		});
	};

	return _.chain(unordered)
		.partition(function(propVal){
			return propVal.isRequired();
		})
		.map(function(propValsGroup, i){
			return _.partition(propValsGroup, function(propVals){
				return (i == 0) ^ !propVals.isEmpty();
			});
		})
		.flatten(true)
		.map(sortAlphabetically)
		.flatten()
		.value();
};

module.exports = Individual;
