//////////////////////////////////////////////////////////////////////////////////////////
//
// Implementation of a simple graph client for the ArangoDB.
//
// Copyright triAGENS GmbH Cologne and The University of York
//
//////////////////////////////////////////////////////////////////////////////////////////

package com.arangodb.tinkerpop.gremlin.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.tinkerpop.gremlin.structure.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.arangodb.ArangoCollection;
import com.arangodb.ArangoCursor;
import com.arangodb.ArangoDB;
import com.arangodb.ArangoDBException;
import com.arangodb.ArangoDatabase;
import com.arangodb.ArangoGraph;
import com.arangodb.entity.EdgeDefinition;
import com.arangodb.entity.EdgeUpdateEntity;
import com.arangodb.entity.GraphEntity;
import com.arangodb.entity.VertexEntity;
import com.arangodb.entity.VertexUpdateEntity;
import com.arangodb.model.AqlQueryOptions;
import com.arangodb.model.GraphCreateOptions;
import com.arangodb.tinkerpop.gremlin.client.ArangoDBQuery.QueryType;
import com.arangodb.tinkerpop.gremlin.structure.ArangoDBGraph;
import com.arangodb.tinkerpop.gremlin.structure.ArangoDBVertex;
import com.arangodb.tinkerpop.gremlin.utils.ArangoDBUtil;


/**
 * The arangodb graph client class handles the HTTP connection to arangodb and performs database
 * operations on the ArangoDatabase.
 *
 * @author Achim Brandt (http://www.triagens.de)
 * @author Johannes Gocke (http://www.triagens.de)
 * @author Guido Schwab (http://www.triagens.de)
 * @author Jan Steemann (http://www.triagens.de)
 * @author Horacio Hoyos Rodriguez (@horaciohoyosr)
 */

public class ArangoDBGraphClient {
	
	/** The Logger. */
	
	private static final Logger logger = LoggerFactory.getLogger(ArangoDBGraphClient.class);

	/** The ArangoDB driver. */
	
	private ArangoDB driver;
	
	/** The ArangoDB database. */
	
	private ArangoDatabase db;

	/** The batch size. */
	
	private int batchSize;
	
	/**
	 * Create a simple graph client and connect to the provided db. If the DB does not exist,
	 * the driver will try to create one
	 *
	 * @param properties 				the ArangoDB configuration properties
	 * @param dbname 					the ArangoDB name to connect to or create
	 * @param batchSize					the size of the batch mode chunks
	 * @throws ArangoDBGraphException 	If the db does not exist and cannot be created
	 */

	public ArangoDBGraphClient(
		Properties properties,
		String dbname,
		int batchSize)
		throws ArangoDBGraphException {
		this(properties, dbname, batchSize, true);
	}
	
	/**
	 * Create a simple graph client and connect to the provided db. The create flag controls what is the
	 * behaviour if the db is not found
	 *
	 * @param properties 				the ArangoDB configuration properties
	 * @param dbname 					the ArangoDB name to connect to or create
	 * @param batchSize					the size of the batch mode chunks
	 * @param create					if true, the driver will attempt to crate the DB if it does not exist
	 * @throws ArangoDBGraphException 	If the db does not exist and cannot be created
	 */
	
	public ArangoDBGraphClient(
		Properties properties, 
		String dbname, 
		int batchSize,
		boolean create) 
		throws ArangoDBGraphException {	
		logger.info("Initiating the ArangoDb Client");
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		try {
			properties.store(os, null);
			InputStream targetStream = new ByteArrayInputStream(os.toByteArray());
			driver = new ArangoDB.Builder().loadProperties(targetStream)
					//.registerModule(new ArangoDBGraphModule())
					.build();
		} catch (IOException e) {
			throw new ArangoDBGraphException("Unable to read properties", e);
		}
		db = driver.db(dbname);
		if (create) {
			if (!db.exists()) {
				logger.info("DB not found, attemtping to create it.");
				try {
					if (!driver.createDatabase(dbname)) {
						throw new ArangoDBGraphException("Unable to crate the database " + dbname);
					}
				}
				catch (ArangoDBException ex) {
					throw ArangoDBExceptions.getArangoDBException(ex);
				}
			}
		}
		else {
			if (!db.exists()) {
				logger.error("Database does not exist, or the user has no access");
				throw new ArangoDBGraphException(String.format("DB not found or user has no access: {}@{}",
						properties.getProperty("arangodb.user"), dbname));
			}
		}
		this.batchSize = batchSize;
	}

	/**
	 * Shutdown the client and free resources.
	 */
	
	public void shutdown() {
		logger.debug("Shutdown");
		if (db != null) {
			if (db.exists()) {
				db.clearQueryCache();
			}
		}
		if (driver != null) driver.shutdown();
		db = null;
		driver = null;
	}
	
	/**
	 * Drop the graph and its related collections.
	 *
	 * @param graph 					the graph to clear
	 * @throws ArangoDBGraphException	if there was an error dropping the graph and its collections
	 */
	
	public void clear(ArangoDBGraph graph) throws ArangoDBGraphException {
		logger.info("Clear {}", graph.name());
		deleteGraph(graph.name());
	}

	/**
	 * Request the version of ArangoDB.
	 *
	 * @return the Version number
	 * @throws ArangoDBGraphException if user has no access to the db
	 */
	
	public String getVersion() throws ArangoDBGraphException {
		try {
			return db.getVersion().getVersion();
		} catch (ArangoDBException ex) {
            throw ArangoDBExceptions.getArangoDBException(ex);
        }
	}
	
	
	/**
	 * Batch size.
	 *
	 * @return the batchsize
	 */
	
	public Integer batchSize() {
		return batchSize;
	}
	
	/**
	 * Gets the driver.
	 *
	 * @return the driver
	 */
	
	public ArangoDB getDriver() {
		return driver;
	}
	
	/**
	 * Gets the database.
	 *
	 * @return the ArangoDB
	 */
	
	public ArangoDatabase getDB() {
		return db;
	}

	/**
	 * Test if the db exists.
	 *
	 * @return true if the db exists
	 */
	
	public boolean dbExists() {
		return db == null ? false: db.exists();
	}
	
	/**
	 * Delete the current database accessed by the driver.
	 *
	 * @throws ArangoDBGraphException if there was an error
	 */
	
	public void deleteDb() throws ArangoDBGraphException {
		logger.info("Delete current db");
		if (db !=null) {
			try {
				db.drop();
			} catch (ArangoDBException e) {
                throw ArangoDBExceptions.getArangoDBException(e);
			}
		}
	}
	
	/**
	 * Get a document from the database. The method is generic so we it can be used to retrieve
	 * vertices, properties or variables.
	 *
	 * @param <V> the value type
	 * @param graph         			the graph
	 * @param id            			the id (key) of the document
	 * @param collection 				the collection from which the document is retrieved
	 * @param docClass the doc class
	 * @return the document
	 * @throws ArangoDBGraphException 	If there was an error retrieving the document
	 */
	
	public <V extends ArangoDBBaseDocument> V getDocument(
		ArangoDBGraph graph,
		String id,
		String collection,
		Class<V> docClass) {
		logger.debug("Get document with id {} from {}:{}", id, graph.name(), collection);
		V result;
		try {
			result = db.graph(graph.name())
					.vertexCollection(ArangoDBUtil.getCollectioName(graph.name(), collection)).getVertex(id, docClass);
		} catch (ArangoDBException e) {
			logger.error("Failed to retrieve vertex: {}", e.getErrorMessage());
			throw new ArangoDBGraphException("Failed to retrieve vertex.", e);
		}
		result.collection(collection);
		result.graph(graph);
		return result;
	}
	
	/**
	 * Insert a ArangoDBBaseDocument in the graph. The document is updated with the id, rev and key
	 * (if not * present) 
	 * @param document 					the document
	 *
	 * @throws ArangoDBGraphException 	If there was an error inserting the document
	 */
	
	public void insertDocument(
		ArangoDBBaseDocument document) {
		String graphName;
		try {
			graphName = document.graph().name();
		} catch (NullPointerException ex) {
			logger.error("Document not paired: {}", document);
			throw new ArangoDBGraphException("Document does not have a graph. Can only delete paired documents.");
		}
		
		logger.debug("Insert document {} in {}", document, graphName);
		VertexEntity vertexEntity;
		try {
			vertexEntity = db.graph(graphName)
					.vertexCollection(ArangoDBUtil.getCollectioName(graphName, document.collection()))
					.insertVertex(document);
		} catch (ArangoDBException e) {
			logger.error("Failed to insert document: {}", e.getMessage());
            throw ArangoDBExceptions.getArangoDBException(e);
		}
		document._id(vertexEntity.getId());
		document._rev(vertexEntity.getRev());
		if (document._key() == null) {
			document._key(vertexEntity.getKey());
		}
		document.setPaired(true);
	}

	/**
	 * Delete a document from the graph.
	 * @param document            		the document to delete
	 *
	 * @throws ArangoDBGraphException 	If there was an error deleting the document
	 */
	
	public void deleteDocument(
		ArangoDBBaseDocument document) {
		String graphName;
		try {
			graphName = document.graph().name();
		} catch (NullPointerException ex) {
			logger.error("Document not paired: {}", document);
			throw new ArangoDBGraphException("Document does not have a graph. Can only delete paired documents.");
		}
		logger.debug("Delete document {} in {}", document, graphName);
		try {
			db.graph(graphName)
			.vertexCollection(ArangoDBUtil.getCollectioName(graphName, document.collection()))
			.deleteVertex(document._key());
		} catch (ArangoDBException e) {
			logger.error("Failed to delete document: {}", e.getErrorMessage());
            throw ArangoDBExceptions.getArangoDBException(e);
		}
		document.setPaired(false);
	}
	
	/**
	 * Update the document in the graph.
	 * @param document 					the document
	 *
	 * @throws ArangoDBGraphException 	If there was an error updating the document
	 */
	
	public void updateDocument(
		ArangoDBBaseDocument document) {
		String graphName;
		try {
			graphName = document.graph().name();
		} catch (NullPointerException ex) {
			logger.error("Document not paired: {}", document);
			throw new ArangoDBGraphException("Document does not have a graph. Can only delete paired documents.");
		}
		logger.debug("Update document {} in {}", document, graphName);
		VertexUpdateEntity vertexEntity;
		try {
			vertexEntity = db.graph(graphName)
					.vertexCollection(ArangoDBUtil.getCollectioName(graphName, document.collection()))
					.updateVertex(document._key(), document);
		} catch (ArangoDBException e) {
			logger.error("Failed to update document: {}", e.getErrorMessage());
            throw ArangoDBExceptions.getArangoDBException(e);
		}
		logger.info("Document updated, new rev {}", vertexEntity.getRev());
		document._rev(vertexEntity.getRev());
	}
	
	/**
	 * Get an edge from the graph.
	 *
	 * @param <V> the value type
	 * @param graph            			the graph
	 * @param id            			the id (key) of the edge
	 * @param collection 				the collection from which the edge is retrieved
	 * @param claszz the claszz
	 * @return the edge
	 * @throws ArangoDBGraphException 	If there was an error retrieving the edge
	 */
	
	public <V extends ArangoDBBaseEdge> V getEdge(
		ArangoDBGraph graph,
		String id,
		String collection,
        Class<V> claszz) {
		logger.debug("Get edge {} from {}:{}", id, graph.name(), collection);
		V result;
		try {
			result = db.graph(graph.name())
					.edgeCollection(ArangoDBUtil.getCollectioName(graph.name(), collection))
					.getEdge(id, claszz);
		} catch (ArangoDBException e) {
			logger.error("Failed to retrieve edge: {}", e.getErrorMessage());
            throw ArangoDBExceptions.getArangoDBException(e);
		}
		result.collection(collection);
		result.graph(graph);
		return result;
	}

	/**
	 * Insert an edge in the graph. The edge is updated with the id, rev and key (if not
	 * present) 
	 * @param edge            			the edge
	 * @throws ArangoDBGraphException 	If there was an error inserting the edge
	 */

	public void insertEdge(
		ArangoDBBaseEdge edge) {
		String graphName;
		try {
			graphName = edge.graph().name();
		} catch (NullPointerException ex) {
			logger.error("Edge not paired: {}", edge);
			throw new ArangoDBGraphException("Edge does not have a graph. Can only delete paired edges.");
		}
		logger.debug("Insert edge {} in {}", edge, graphName);
		try {
			db.graph(graphName)
					.edgeCollection(ArangoDBUtil.getCollectioName(graphName, edge.collection()))
					.insertEdge(edge);
		} catch (ArangoDBException e) {
			logger.error("Failed to insert edge: {}", e.getErrorMessage());
            throw ArangoDBExceptions.getArangoDBException(e);
		}
		edge.setPaired(true);
	}

	/**
	 * Delete an edge from the graph.
	 * @param edge            			the edge
	 * @throws ArangoDBGraphException 	If there was an error deleting the edge
	 */

	public void deleteEdge(
		ArangoDBBaseEdge edge) {
		String graphName;
		try {
			graphName = edge.graph().name();
		} catch (NullPointerException ex) {
			logger.error("Edge not paired: {}", edge);
			throw new ArangoDBGraphException("Edge does not have a graph. Can only delete paired edges.");
		}
		logger.debug("Delete edge {} in {}", edge, graphName);
		try {
			db.graph(graphName)
			.edgeCollection(ArangoDBUtil.getCollectioName(graphName, edge.collection()))
			.deleteEdge(edge._key());
		} catch (ArangoDBException e) {
			logger.error("Failed to delete vertex: {}", e.getErrorMessage());
            throw ArangoDBExceptions.getArangoDBException(e);
		}
		edge.setPaired(false);
	}
	
	/**
	 * Update the edge in the graph.
	 * @param edge 						the edge
	 * @throws ArangoDBGraphException 	If there was an error updating the edge
	 */
	
	public void updateEdge(
		ArangoDBBaseEdge edge) {
		String graphName;
		try {
			graphName = edge.graph().name();
		} catch (NullPointerException ex) {
			logger.error("Edge not paired: {}", edge);
			throw new ArangoDBGraphException("Edge does not have a graph. Can only delete paired edges.");
		}
		logger.debug("Update edge {} in {}", edge, graphName);
		EdgeUpdateEntity edgeEntity;
		try {
			edgeEntity = db.graph(graphName)
					.edgeCollection(ArangoDBUtil.getCollectioName(graphName, edge.collection()))
					.updateEdge(edge._key(), edge);
		} catch (ArangoDBException e) {
			logger.error("Failed to update vertex: {}", e.getErrorMessage());
            throw ArangoDBExceptions.getArangoDBException(e);
		}
		logger.info("Edge updated, new rev {}", edgeEntity.getRev());
		edge._rev(edgeEntity.getRev());
	}
	
	/**
	 * Create a query to get all the edges of a vertex. 
	 *
	 * @param graph          			the graph
	 * @param vertex            		the vertex
	 * @param edgeLabels        		a list of edge labels to follow, empty if all type of edges
	 * @param direction         		the direction of the edges
	 * @return ArangoDBBaseQuery		the query object
	 * @throws ArangoDBException the arango DB exception
	 */

	public ArangoDBQuery getVertexEdges(
		ArangoDBGraph graph,
		ArangoDBVertex vertex,
		List<String> edgeLabels,
		Direction direction)
		throws ArangoDBException {
		logger.debug("Get Vertex's {}:{} Edges, in {}, from collections {}", vertex, direction, graph.name(), edgeLabels);
		ArangoDBQuery.Direction arangoDirection = null;
		switch(direction) {
		case BOTH:
			arangoDirection = ArangoDBQuery.Direction.ALL;
			break;
		case IN:
			arangoDirection = ArangoDBQuery.Direction.IN;
			break;
		case OUT:
			arangoDirection = ArangoDBQuery.Direction.OUT;
			break;
		}
		logger.debug("Creating query");
		return new ArangoDBQuery(graph, this, QueryType.GRAPH_EDGES).setStartVertex(vertex)
				.setLabelsFilter(edgeLabels).setDirection(arangoDirection);
	}
	
	/**
	 * Create a query to get all neighbours of a document.
	 *
	 * @param graph					the simple graph
	 * @param document              the document
	 * @param edgeLabelsFilter      a list of edge types to follow
	 * @param direction             a direction
	 * @param propertyFilter		Filter the neighbours on the given property:value values
     * @return ArangoDBBaseQuery	the query object
	 */

	public ArangoDBQuery getDocumentNeighbors(
            ArangoDBGraph graph,
            ArangoDBBaseDocument document,
            List<String> edgeLabelsFilter,
            Direction direction,
            ArangoDBPropertyFilter propertyFilter) {
		logger.debug("Get Vertex's {}:{} Neighbors, in {}, from collections {}", document, direction, graph.name(), edgeLabelsFilter);
		ArangoDBQuery.Direction arangoDirection = null;
		switch(direction) {
		case BOTH:
			arangoDirection = ArangoDBQuery.Direction.ALL;
			break;
		case IN:
			arangoDirection = ArangoDBQuery.Direction.IN;
			break;
		case OUT:
			arangoDirection = ArangoDBQuery.Direction.OUT;
			break;
		}
		return new ArangoDBQuery(graph, this, QueryType.GRAPH_NEIGHBORS)
				.setDirection(arangoDirection).setStartVertex(document).setLabelsFilter(edgeLabelsFilter)
				.setPropertyFilter(propertyFilter);
	}
	
	
	/**
	 * Create a query to get all vertices of a graph.
	 *
	 * @param graph            		the graph
	 * @param keys 					the keys (Tinkerpop ids) to filter the results
	 * @return ArangoDBBaseQuery 	the query object
	 */

	public ArangoDBQuery getGraphVertices(
		ArangoDBGraph graph,
		List<String> keys) {
		logger.debug("Get all {} graph vertices, filterd by ids: {}", graph.name(), keys);
		return new ArangoDBQuery(graph, this, QueryType.GRAPH_VERTICES)
				.setKeysFilter(keys);
	}
	
	/**
	 * Create a query to get all edges of a graph.
	 *
	 * @param graph 				the graph
	 * @param keys 					the keys (Tinkerpop ids) to filter the results
	 * @return ArangoDBBaseQuery	the query object
	 */
	
	public ArangoDBQuery getGraphEdges(
		ArangoDBGraph graph,
		List<String> keys) {
		logger.debug("Get all {} graph edges, filterd by ids: {}", graph.name(), keys);
		return new ArangoDBQuery(graph, this, QueryType.GRAPH_EDGES)
				.setKeysFilter(keys);
	}

    /**
     * Get the properties of the document, filtering by key name.
     *
     * @param document the document
     * @param keys the keys
     * @return the document properties
     */
	
    public ArangoDBQuery getDocumentProperties(
        ArangoDBBaseDocument document,
        String... keys) {

	    throw new UnsupportedOperationException("Unimplemented.");
    }
	
	/**
	 * Execute AQL query.
	 *
	 * @param <T> 						the generic type of the returned values
	 * @param query 					the query string
	 * @param bindVars 					the value of the bind parameters
	 * @param aqlQueryOptions 			the aql query options
	 * @param type            			The type of the result (POJO class, VPackSlice, String for Json, or Collection/List/Map)
	 * @return the cursor result
	 * @throws ArangoDBGraphException	if executing the query raised an exception
	 */

	public <T> ArangoCursor<T> executeAqlQuery(
		String query,
		Map<String, Object> bindVars,
		AqlQueryOptions aqlQueryOptions,
		final Class<T> type)
		throws ArangoDBGraphException {
		logger.debug("Executing AQL query ({}) against db, with bind vars: {}", query, bindVars);
		try {
			return db.query(query, bindVars, aqlQueryOptions, type);
		} catch (ArangoDBException e) {
			logger.error("Error executing query", e);
            throw ArangoDBExceptions.getArangoDBException(e);
		}
	}
	
	/**
	 * Delete a graph from the db, and all its collections.
	 *
	 * @param name the name of the graph to delete
	 * @return true, if the graph was deleted
	 */
	
	public boolean deleteGraph(String name) {
		return deleteGraph(name, true);
	}
	
	/**
	 * Delete a graph from the db. If dropCollection is true, then all the graph collections are also 
	 * dropped
	 *
	 * @param name 				the name
	 * @param dropCollections 	true to drop the graph collections too
	 * @return true if the graph was deleted
	 */
	
	public boolean deleteGraph(
		String name,
		boolean dropCollections) {
		if (db != null) {
			ArangoGraph graph = db.graph(name);
			if (graph.exists()) {
				Collection<String> edgeDefinitions = dropCollections ? graph.getEdgeDefinitions() : Collections.emptyList();
				Collection<String> vertexCollections = dropCollections ? graph.getVertexCollections(): Collections.emptyList();;
				// Drop graph first because it will break if the graph collections do not exist
				graph.drop();
				for (String definitionName : edgeDefinitions) {
					String collectioName = definitionName;
					if (db.collection(collectioName).exists()) {
						db.collection(collectioName).drop();
					}
				}
				for (String vc : vertexCollections) {
					String collectioName = vc;
					if (db.collection(collectioName).exists()) {
						db.collection(collectioName).drop();
					}
				}
				return true;
			} else {
				try {
					graph.drop();
				} catch (ArangoDBException e) {
                    //throw ArangoDBExceptions.getArangoDBException(e);
				}
			}
		}
		return false;
	}

	/**
	 * Delete collection.
	 *
	 * @param name the name
	 * @return true, if successful
	 */
	
	public boolean deleteCollection(String name) {
		ArangoCollection collection = db.collection(name);
		if (collection.exists()) {
			collection.drop();
			return collection.exists();
		}
		return false;
	}
	
	/**
	 * Create a new graph.
	 *
	 * @param name            			the name of the new graph
	 * @param verticesCollectionNames 	the list of vertex collection names
	 * @param edgesCollectionNames 		the list of edge collection names
	 * @param relations 				the relations, if any
	 * @throws ArangoDBGraphException 	If the graph can not be created
	 */
	
	public void createGraph(String name,
		List<String> verticesCollectionNames,
		List<String> edgesCollectionNames,
		List<String> relations)
		throws ArangoDBGraphException {
		this.createGraph(name, verticesCollectionNames, edgesCollectionNames, relations, null);
	}
	
	/**
	 * Create a new graph.
	 *
	 * @param name 						the name of the new graph
	 * @param verticesCollectionNames 	the list of vertex collection names
	 * @param edgesCollectionNames 		the list of edge collection names
	 * @param relations the relations	the relations, if any
	 * @param options 					additional graph options
	 * @return 
	 * @throws ArangoDBGraphException 	If the graph can not be created
	 */
	
	public ArangoGraph createGraph(String name,
		List<String> verticesCollectionNames,
		List<String> edgesCollectionNames,
		List<String> relations,
		GraphCreateOptions options)
		throws ArangoDBGraphException {
		logger.info("Creating graph {}", name);
		final Collection<EdgeDefinition> edgeDefinitions = new ArrayList<>();
		if (relations.isEmpty()) {
			logger.info("No relations, creating default one.");
			edgeDefinitions.addAll(ArangoDBUtil.createDefaultEdgeDefinitions(name, verticesCollectionNames, edgesCollectionNames));
		} else {
			for (String value : relations) {
				EdgeDefinition ed = ArangoDBUtil.relationPropertyToEdgeDefinition(name, value);
				edgeDefinitions.add(ed);
			}
		}
		//edgeDefinitions.addAll(ArangoDBUtil.createPropertyEdgeDefinitions(name, verticesCollectionNames, edgesCollectionNames));
		try {
			logger.info("Creating graph in database.");
			db.createGraph(name, edgeDefinitions, options);
		} catch (ArangoDBException e) {
            logger.info("Error creating graph in database.", e);
            throw ArangoDBExceptions.getArangoDBException(e);
        }
		ArangoGraph g = db.graph(name);
		EdgeDefinition ed = ArangoDBUtil.createPropertyEdgeDefinitions(name, verticesCollectionNames, edgesCollectionNames);
		g.addEdgeDefinition(ed);
		return g;
	}


	/**
	 * Get a graph by name.
	 *
	 * @param name            the name of the new graph
	 * @return the graph or null if the graph was not found
	 */
	
	public ArangoGraph getGraph(String name) {
		return db.graph(name);
	}

    /**
     * Common exceptions to use with an ArangoDB. This class is intended to translate ArangoDB
     * error codes into meaningful exceptions with standard messages. ArangoDBException exception
     * is a RuntimeException intended to stop execution.
     */
	
    public static class ArangoDBExceptions {
    	
    	/** The error code. */
	    public static Pattern ERROR_CODE = Pattern.compile("^Response:\\s\\d+,\\sError:\\s(\\d+)\\s-\\s([a-z\\s]+).+");
    	
        /**
         * Instantiates a new arango DB exceptions.
         */
        private ArangoDBExceptions() {
        }
        
        /**
         * Translate ArangoDB Error code into exception (@see <a href="https://docs.arangodb.com/latest/Manual/Appendix/ErrorCodes.html">Error codes</a>)
         *
         * @param ex the ex
         * @return The ArangoDBClientException
         */
        public static ArangoDBGraphException getArangoDBException(ArangoDBException ex) {
        	String errorMessage = ex.getMessage();
			Matcher m = ERROR_CODE.matcher(errorMessage);
        	if (m.matches()) {
        		int code = Integer.parseInt(m.group(1));
        		String msg = m.group(2);
        		switch ((int)code/100) {
        		case 10:	// Internal ArangoDB storage errors
        			return new ArangoDBGraphException(String.format("Internal ArangoDB storage error (%s): %s", code, msg));
        		case 11:
        			return new ArangoDBGraphException(String.format("External ArangoDB storage error (%s): %s", code, msg));
        		case 12:
        			return new ArangoDBGraphException(String.format("General ArangoDB storage error (%s): %s", code, msg));
        		case 13:
        			return new ArangoDBGraphException(String.format("Checked ArangoDB storage error (%s): %s", code, msg));
        		case 14:
        			return new ArangoDBGraphException(String.format("ArangoDB replication/cluster error (%s): %s", code, msg));
        		case 15:
        			return new ArangoDBGraphException(String.format("ArangoDB query error (%s): %s", code, msg));
        		case 19:
        			return new ArangoDBGraphException(String.format("Graph / traversal errors (%s): %s", code, msg));
        		}
        	}
        	return new ArangoDBGraphException("General ArangoDB error (unkown error)");
        }

        /** The name to long. */
        public static String NAME_TO_LONG = "Name is too long: {} bytes (max 64 bytes for labels, 256 for keys)";

        /**
         * Gets the naming convention error.
         *
         * @param cause the cause
         * @param details the details
         * @return the naming convention error
         */
        public static ArangoDBGraphException getNamingConventionError(String cause, String details) {
            return new ArangoDBGraphException("The provided label or key name does not satisfy the naming conventions." +
                    String.format(cause, details));
        }

        /**
         * Error persisting elmenent property.
         *
         * @param ex the ex
         * @return the arango DB graph exception
         */
        public static ArangoDBGraphException errorPersistingElmenentProperty(ArangoDBGraphException ex) {
            return new ArangoDBGraphException("Error persisting property in element. ", ex);
        }
    }

    // TODO Decide what of these methods should be restored.
//	
//	/**
//	 * Creates vertices (bulk import).
//	 *
//	 * @param graph            The graph
//	 * @param vertices            The list of new vertices
//	 * @param details            True, for details
//	 * @return a ImportResultEntity object
//	 * @throws ArangoDBException             if an error occurs
//	 */
//	public ImportResultEntity createVertices(
//		ArangoDBSimpleGraph graph,
//		List<ArangoDBSimpleVertex> vertices,
//		boolean details) throws ArangoDBException {
//
//		List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
//
//		for (ArangoDBSimpleVertex v : vertices) {
//			values.add(v.getProperties());
//		}
//
//		try {
//			return driver.importDocuments(graph.getVertexCollection(), true, values);
//		} catch (ArangoException e) {
//			throw new ArangoDBException(e);
//		}
//	}
//
//	/**
//	 * Creates edges (bulk import).
//	 *
//	 * @param graph            The graph
//	 * @param edges            The list of new edges
//	 * @param details            True, for details
//	 * @return a ImportResultEntity object
//	 * @throws ArangoDBException             if an error occurs
//	 */
//	public ImportResultEntity createEdges(ArangoDBSimpleGraph graph, List<ArangoDBSimpleEdge> edges, boolean details)
//			throws ArangoDBException {
//
//		List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
//
//		for (ArangoDBSimpleEdge e : edges) {
//			values.add(e.getProperties());
//		}
//
//		try {
//			return driver.importDocuments(graph.getEdgeCollection(), true, values);
//		} catch (ArangoException e) {
//			throw new ArangoDBException(e);
//		}
//	}

//	/**
//	 * Create an index on collection keys.
//	 *
//	 * @param graph            the simple graph
//	 * @param type            the index type ("cap", "geo", "hash", "skiplist")
//	 * @param unique            true for a unique key
//	 * @param fields            a list of key fields
//	 * @return ArangoDBIndex the index
//	 * @throws ArangoDBException             if creation failed
//	 */
//
//	public ArangoDBIndex createVertexIndex(
//		ArangoDBSimpleGraph graph,
//		IndexType type,
//		boolean unique,
//		List<String> fields) throws ArangoDBException {
//		return createIndex(graph.getVertexCollection(), type, unique, fields);
//	}
//
//	/**
//	 * Create an index on collection keys.
//	 *
//	 * @param graph            the simple graph
//	 * @param type            the index type ("cap", "geo", "hash", "skiplist")
//	 * @param unique            true for a unique key
//	 * @param fields            a list of key fields
//	 * @return ArangoDBIndex the index
//	 * @throws ArangoDBException             if creation failed
//	 */
//
//	public ArangoDBIndex createEdgeIndex(ArangoDBSimpleGraph graph, IndexType type, boolean unique, List<String> fields)
//			throws ArangoDBException {
//		return createIndex(graph.getEdgeCollection(), type, unique, fields);
//	}
//
//	/**
//	 * Get an index.
//	 *
//	 * @param id            id of the index
//	 * @return ArangoDBIndex the index, or null if the index was not found
//	 * @throws ArangoDBException             if creation failed
//	 */
//
//	public ArangoDBIndex getIndex(String id) throws ArangoDBException {
//		IndexEntity index;
//		try {
//			index = driver.getIndex(id);
//		} catch (ArangoException e) {
//
//			if (e.getErrorNumber() == ErrorNums.ERROR_ARANGO_INDEX_NOT_FOUND) {
//				return null;
//			}
//
//			throw new ArangoDBException(e);
//		}
//		return new ArangoDBIndex(index);
//	}
//
//	/**
//	 * Returns the indices of the vertex collection.
//	 *
//	 * @param graph            The graph
//	 * @return List of indices
//	 * @throws ArangoDBException             if an error occurs
//	 */
//	public List<ArangoDBIndex> getVertexIndices(ArangoDBSimpleGraph graph) throws ArangoDBException {
//		return getIndices(graph.getVertexCollection());
//	}
//
//	/**
//	 * Returns the indices of the edge collection.
//	 *
//	 * @param graph            The graph
//	 * @return List of indices
//	 * @throws ArangoDBException             if an error occurs
//	 */
//	public List<ArangoDBIndex> getEdgeIndices(ArangoDBSimpleGraph graph) throws ArangoDBException {
//		return getIndices(graph.getEdgeCollection());
//	}
//
//	/**
//	 * Deletes an index.
//	 *
//	 * @param id            The identifier of the index
//	 * @return true, if the index was deleted
//	 * @throws ArangoDBException             if an error occurs
//	 */
//	public boolean deleteIndex(String id) throws ArangoDBException {
//		try {
//			driver.deleteIndex(id);
//		} catch (ArangoException e) {
//			throw new ArangoDBException(e);
//		}
//
//		return true;
//	}
//
//	/**
//	 * Create an index on collection keys.
//	 *
//	 * @param collectionName            the collection name
//	 * @param type            the index type ("cap", "geo", "hash", "skiplist")
//	 * @param unique            true for a unique key
//	 * @param fields            a list of key fields
//	 * @return ArangoDBIndex the index
//	 * @throws ArangoDBException             if creation failed
//	 */
//
//	private ArangoDBIndex createIndex(String collectionName, IndexType type, boolean unique, List<String> fields)
//			throws ArangoDBException {
//
//		IndexEntity indexEntity;
//		try {
//			indexEntity = driver.createIndex(collectionName, type, unique, fields.toArray(new String[0]));
//		} catch (ArangoException e) {
//			throw new ArangoDBException(e);
//		}
//
//		return new ArangoDBIndex(indexEntity);
//	}
//
//	/**
//	 * Get the List of indices of a collection.
//	 *
//	 * @param collectionName            the collection name
//	 * @return Vector<ArangoDBIndex> List of indices
//	 * @throws ArangoDBException             if creation failed
//	 */
//
//	private List<ArangoDBIndex> getIndices(String collectionName) throws ArangoDBException {
//		List<ArangoDBIndex> indices = new ArrayList<ArangoDBIndex>();
//
//		IndexesEntity indexes;
//		try {
//			indexes = driver.getIndexes(collectionName);
//		} catch (ArangoException e) {
//			throw new ArangoDBException(e);
//		}
//
//		for (IndexEntity indexEntity : indexes.getIndexes()) {
//			indices.add(new ArangoDBIndex(indexEntity));
//		}
//
//		return indices;
//	}
//
//	/**
//	 * Returns the current connection configuration.
//	 *
//	 * @param collectionName the collection name
//	 * @return the configuration
//	 * @throws ArangoDBException the arango DB exception
//	 */
////	public ArangoDBConfiguration getConfiguration() {
////		return configuration;
////	}
//
//	/**
//	 * Truncates a collection
//	 * 
//	 * @param collectionName
//	 */
//	public void truncateCollection(String collectionName) throws ArangoDBException {
//		try {
//			driver.truncateCollection(collectionName);
//		} catch (ArangoException e) {
//			throw new ArangoDBException(e);
//		}
//	}

}