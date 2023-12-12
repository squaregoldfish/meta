package se.lu.nateko.cp.meta.services.upload

import java.net.URI

import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.model.vocabulary.RDFS
import org.eclipse.rdf4j.model.IRI

import se.lu.nateko.cp.meta.core.crypto.Sha256Sum
import se.lu.nateko.cp.meta.core.data.*

import se.lu.nateko.cp.meta.instanceserver.InstanceServer
import se.lu.nateko.cp.meta.instanceserver.TriplestoreConnection
import se.lu.nateko.cp.meta.instanceserver.TriplestoreConnection.*
import se.lu.nateko.cp.meta.services.CpVocab
import se.lu.nateko.cp.meta.services.CpmetaVocab
import se.lu.nateko.cp.meta.utils.rdf4j.*
import se.lu.nateko.cp.meta.utils.*
import se.lu.nateko.cp.meta.services.citation.CitationMaker
import eu.icoscp.envri.Envri

//TODO Remove CollectionFetcherLite
class CollectionFetcherLite(val server: InstanceServer, vocab: CpVocab) extends CpmetaFetcher {

	val memberProp = metaVocab.dcterms.hasPart

	def getTitle(collUri: IRI): String = getSingleString(collUri, metaVocab.dcterms.title)

	def collectionExists(collUri: IRI): Boolean =
		server.hasStatement(collUri, RDF.TYPE, metaVocab.collectionClass)

	def fetchLite(collUri: IRI): Option[UriResource] = {
		if(collectionExists(collUri)) Some(
			UriResource(collUri.toJava, Some(getTitle(collUri)), Nil)
		) else None
	}

	def getParentCollections(dobj: IRI): Seq[UriResource] = {
		val allIris = server.getStatements(None, Some(memberProp), Some(dobj))
			.map(_.getSubject)
			.collect{case iri: IRI => iri}
			.toIndexedSeq

		val deprecatedColls = allIris.flatMap(getPreviousVersions).toSet

		allIris.flatMap(fetchLite).filterNot(res => deprecatedColls.contains(res.uri))
	}

	def getCreatorIfCollExists(hash: Sha256Sum)(using Envri): Option[IRI] = {
		val collUri = vocab.getCollection(hash)
		server.getUriValues(collUri, metaVocab.dcterms.creator, InstanceServer.AtMostOne).headOption
	}

	def collectionExists(coll: Sha256Sum)(using Envri): Boolean = collectionExists(vocab.getCollection(coll))
}


class CollectionReader(val metaVocab: CpmetaVocab, citer: CitableItem => References) extends CpmetaReader:

	import metaVocab.{dcterms => dct}

	private def getTitle(collUri: IRI): TSC2V[String] = getSingleString(collUri, dct.title)

	def collectionExists(collUri: IRI): TSC2[Boolean] = resourceHasType(collUri, metaVocab.collectionClass)

	def fetchCollLite(collUri: IRI): TSC2V[UriResource] =
		if collectionExists(collUri) then
			getTitle(collUri).map: title =>
				UriResource(collUri.toJava, Some(title), Nil)
		else Validated.error("collection does not exist")

	def getParentCollections(dobj: IRI): TSC2V[Seq[UriResource]] =
		val allParentColls = getStatements(None, Some(dct.hasPart), Some(dobj))
			.map(_.getSubject)
			.collect{case iri: IRI => iri}
			.toIndexedSeq

		val deprecatedSet = allParentColls.flatMap(getPreviousVersions).toSet

		Validated.sequence(allParentColls.filterNot(deprecatedSet.contains).map(fetchCollLite))


	// def getCreatorIfCollExists(hash: Sha256Sum): TSC2V[Option[IRI]] =
	// 	getOptionalUri(vocab.getCollection(hash), dct.creator)


	def fetchStaticColl(collUri: IRI, hashOpt: Option[Sha256Sum]): TSC2V[StaticCollection] =
		if !collectionExists(collUri) then Validated.error(s"Collection $collUri does not exist")
		else getExistingStaticColl(collUri, hashOpt)

	private def getExistingStaticColl(coll: IRI, hashOpt: Option[Sha256Sum] = None): TSC2V[StaticCollection] =

		val membersV = Validated.sequence:
			getUriValues(coll, dct.hasPart).map: item =>
				if collectionExists(item) then getExistingStaticColl(item)
				else getPlainStaticObject(item)(using globalLens)

		for
			creatorUri <- getSingleUri(coll, dct.creator)
			members <- membersV
			creator <- getOrganization(creatorUri)
			title <- getTitle(coll)
			description <- getOptionalString(coll, dct.description)
			doi <- getOptionalString(coll, metaVocab.hasDoi)
			documentationUriOpt <- getOptionalUri(coll, RDFS.SEEALSO)
			documentation <- documentationUriOpt.map(getPlainStaticObject).sinkOption
		yield
			val init = StaticCollection(
				res = coll.toJava,
				hash = hashOpt.getOrElse(Sha256Sum.fromBase64Url(coll.getLocalName).get),
				members = members.sortBy:
					case coll: StaticCollection => coll.title
					case dobj: PlainStaticObject => dobj.name
				,
				creator = creator,
				title = title,
				description = description,
				nextVersion = getNextVersionAsUri(coll),
				latestVersion = getLatestVersion(coll),
				previousVersion = getPreviousVersion(coll).flattenToSeq.headOption.map(_.toJava),
				doi = doi,
				documentation = documentation,
				references = References.empty
			)
			//TODO Consider adding collection-specific logic for licence information
			init.copy(references = citer(init).copy(title = Some(init.title)))

end CollectionReader
