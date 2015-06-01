var ok = {valid: true, errors: []};

function error(message){
	return {valid: false, errors: _.flatten([message])};
}

function stringValidator(s){
	return ok;
}

function doubleValidator(s){
	var number = Number.parseFloat(s);
	return _.isNaN(number) ? error("Not a number!") : ok;
}

function minValueValidator(min){
	return function(s){
		var number = Number.parseFloat(s);
		return number < min ? error("Must be more than or equal to " + min) : ok;
	};
}

function maxValueValidator(max){
	return function(s){
		var number = Number.parseFloat(s);
		return number > max ? error("Must be less than or equal to " + max) : ok;
	};
}

function regexpValueValidator(pattern){
	var regex = new RegExp(pattern);
	return function(s){
		return regex.test(s) ? ok : error([
			"Invalid string format.",
			"Must follow regular expression " + pattern
		]);
	};
}

function oneOfValueValidator(allowedStrings){
	return function(s){
		return _.contains(allowedStrings, s) ? ok : error("Must be one of: " + allowedStrings.join(', '));
	};
}

var xsd = "http://www.w3.org/2001/XMLSchema#";

var dataTypeValidators = _.object([
	[xsd + "string", stringValidator],
	[xsd + "double", doubleValidator],
	[xsd + "float", doubleValidator]
]);

function conditionalValidator(conditionValidator, otherValidators){
	return function(s){
		var conditionValidity = conditionValidator(s);

		if(!conditionValidity.valid) return conditionValidity;

		return aggregatingValidator(otherValidators)(s);
	};
}

function aggregatingValidator(subValidators){
	return function(s){
		return aggregateValidities(
			_.map(subValidators, function(validator){
				return validator(s);
			})
		);
	};
}

function aggregateValidities(validities){
	return {
		valid: _.every(validities, _.property("valid")),
		errors: _.flatten(_.pluck(validities, "errors"))
	};
}


var restrictionValidators = _.object([
	["minValue", minValueValidator],
	["maxValue", maxValueValidator],
	["regExp", regexpValueValidator],
	["oneOf", oneOfValueValidator]
]);

function getValidator(dataRangeDto){

	var dtype = dataRangeDto.dataType;
	var dtypeValidator = dataTypeValidators[dtype];
	if(!dtypeValidator) throw new Error("Unsupported data type: '" + dtype + "'");

	var otherValidators = _.map(dataRangeDto.restrictions || [], function(restriction){

		switch(restriction.type){
			case "minValue": return minValueValidator(restriction.minValue);
			case "maxValue": return maxValueValidator(restriction.maxValue);
			case "regExp":   return regexpValueValidator(restriction.regexp);
			case "oneOf":    return oneOfValueValidator(restriction.values);
			default:         throw new Error("Unsupported data type restriction: '" + restriction.type + "'");
		}

	});

	return conditionalValidator(dtypeValidator, otherValidators);
}

module.exports = {
	getValidator: getValidator,
	aggregateValidities: aggregateValidities
};


