package se.lu.nateko.cp.meta.persistence

import org.eclipse.rdf4j.common.transaction.IsolationLevels
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.repository.Repository

import scala.util.Try
import scala.util.Failure

import se.lu.nateko.cp.meta.api.CloseableIterator
import se.lu.nateko.cp.meta.instanceserver.RdfUpdate
import se.lu.nateko.cp.meta.persistence.postgres.ResultSetIterator.chunkSize
import se.lu.nateko.cp.meta.utils.rdf4j.*


object RdfUpdateLogIngester:

	def ingestIntoMemory(updates: CloseableIterator[RdfUpdate], contexts: IRI*): Repository =
		ingest(updates, Loading.emptyInMemory, false, contexts*)

	def ingest(updates: CloseableIterator[RdfUpdate], repo: Repository, cleanFirst: Boolean, contexts: IRI*): Repository =

		def commitChunk(chunk: Seq[RdfUpdate]): Try[Unit] = repo.transact(
			conn =>
				for update <- chunk do
					if(update.isAssertion)
						conn.add(update.statement, contexts*)
					else
						conn.remove(update.statement, contexts*)
			,
			Some(IsolationLevels.NONE)
		)

		if(cleanFirst) repo
			.transact(_.remove(null, null, null, contexts*), Some(IsolationLevels.NONE))
			.get //throw exception if failed to clean

		try
			updates.sliding(chunkSize, chunkSize).foreach(commitChunk(_).get)
			repo
		finally
			updates.close()

	end ingest

end RdfUpdateLogIngester
