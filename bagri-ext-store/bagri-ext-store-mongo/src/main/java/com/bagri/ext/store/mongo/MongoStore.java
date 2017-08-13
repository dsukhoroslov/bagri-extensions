package com.bagri.ext.store.mongo;

import static com.bagri.core.Constants.pn_schema_format_default;
import static com.bagri.core.api.TransactionManagement.TX_INIT;
import static com.bagri.core.api.TransactionManagement.TX_NO;
import static com.bagri.core.system.DataFormat.df_json;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Projections.include;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bson.json.JsonWriterSettings;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bagri.core.DocumentKey;
import com.bagri.core.api.BagriException;
import com.bagri.core.server.api.DocumentManagement;
import com.bagri.core.server.api.DocumentStore;
import com.bagri.core.server.api.PopulationManagement;
import com.bagri.core.server.api.SchemaRepository;
import com.bagri.core.server.api.impl.DocumentStoreBase;
import com.bagri.core.model.Document;
import com.bagri.support.util.XMLUtils;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.MongoCredential;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;

public class MongoStore extends DocumentStoreBase implements DocumentStore {
	
    private static final Logger logger = LoggerFactory.getLogger(MongoStore.class);
    private static final String _id = "_id";

    private boolean indent = false;
    private boolean toJson = false;
	private MongoClient client;
	private MongoDatabase db;
    private Map<String, MongoCollection<org.bson.Document>> clMap = new HashMap<>();
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void init(Map<String, Object> context) {
		logger.debug("init.enter; got context: {}", context);
		super.setContext(context);

		String ind = (String) context.get("xdm.schema.store.indent");
		if (ind != null) {
			indent = ind.equalsIgnoreCase("true");
		}
		String format = (String) context.get("xdm.schema.store.format");
		if (format != null) {
			toJson = format.equalsIgnoreCase("JSON");
		}
		
		// what about credentials..?
		//MongoCredential credential = MongoCredential.createCredential(userName, database, password);
		
		String uri = (String) context.get("xdm.schema.store.uri"); 
		MongoClientURI mcUri = new MongoClientURI(uri);
		client = new MongoClient(mcUri);
		logger.trace("init; got mongoClient: {}", client);

		String dbName = (String) context.get("xdm.schema.store.database");
		db = client.getDatabase(dbName);
		logger.trace("init; got mongoDatabase: {}", db);
		
	    long count = 0;
		String clNames = (String) context.get("xdm.schema.store.collections");
		boolean all = "*".equals(clNames);
		List<String> clns = Arrays.asList(clNames.split(","));
		logger.info("init; looking for collections: {}; original: {}", clns, clNames);
		for (String clName: db.listCollectionNames()) {
			logger.info("init; mongo collection: {}", clName);
			if (all || clns.contains(clName)) {
				MongoCollection<org.bson.Document> cln = db.getCollection(clName); 
				clMap.put(clName, cln);
				count += cln.count();
				logger.trace("init; got mongoCollection: {}; size: {}", cln, cln.count());
			}
		}
		logger.debug("init.exit; collections {} with total size: {}", clMap.keySet(), count);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void close() {
		client.close();
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public Iterable<DocumentKey> loadAllDocumentKeys() {
		logger.debug("loadAllDocumentKeys.enter");
		SchemaRepository repo = getRepository();
		if (repo == null) {
			logger.debug("loadAllDocumentKeys.exit; repository is not initialized yet");
			return null;
		}

		String id;
		DocumentKey key;
	    Map<DocumentKey, String> keyMap = new HashMap<>();
		for (MongoCollection<org.bson.Document> cln: clMap.values()) {
			String clName = cln.getNamespace().getCollectionName();
			// load _ids only
			MongoCursor<org.bson.Document> cursor = cln.find().projection(include(_id)).iterator();
			while (cursor.hasNext()) {
				org.bson.Document doc = cursor.next();
				id = doc.get(_id).toString();
				int rev = 0;
				do {
					key = repo.getFactory().newDocumentKey(id, rev, 1);
					if (keyMap.containsKey(key)) {
						rev++;
						logger.debug("loadAllDocumentKeys; the key {} already exists; id: {}; increased revision number to {}", key, id, rev);
					} else {
						keyMap.put(key, getMappingKey(id, clName));
						break;
					}
				} while (true);
			}
		}
   		PopulationManagement popManager = repo.getPopulationManagement();
   		popManager.setKeyMappings(keyMap);
		logger.debug("loadAllDocumentKeys.exit; returning {} keys", keyMap.size());
		return keyMap.keySet();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Map<DocumentKey, Document> loadAllDocuments(Collection<DocumentKey> keys) {
		logger.debug("loadAllDocuments.enter; got {} keys", keys.size());
		SchemaRepository repo = getRepository();
		Map<DocumentKey, Document> entries = new HashMap<>(keys.size());
		for (DocumentKey key: keys) {
			Document doc = loadDocumentInternal(repo, key);
			if (doc != null) {
				entries.put(key, doc);
			}
		}
		logger.debug("loadAllDocuments.exit; returning {} entries", entries.size());
		return entries;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Document loadDocument(DocumentKey key) {
		logger.debug("loadDocument.enter; got key {}", key);
		Document doc = loadDocumentInternal(getRepository(), key);
		logger.debug("loadDocument.exit; returning document {}", doc);
		return doc;
	}
	
	private Document loadDocumentInternal(SchemaRepository repo, DocumentKey key) {
   		PopulationManagement popManager = repo.getPopulationManagement(); 
    	String keyMap = popManager.getKeyMapping(key); 
    	if (keyMap == null) {
    		// not clear, what is it
    		logger.info("loadDocument; no mapping found for key: {}", key);
    		return null;
    	}
    	String[] mParts = getMappingParts(keyMap);
    	
    	int[] clns = null;
    	com.bagri.core.system.Collection xcl = repo.getSchema().getCollection(mParts[1]);
    	if (xcl != null) {
    		clns = new int[] {xcl.getId()};
    	}
    	MongoCollection<org.bson.Document> cln = clMap.get(mParts[1]);

   		Object oid;
   		Date creDate;
   		try {
   			oid = new ObjectId(mParts[0]);
   			creDate = ((ObjectId) oid).getDate();
   		} catch (IllegalArgumentException ex) {
   			logger.info("loadDocumentInternal; error producing OID", ex);
   			oid = mParts[0];
   			creDate = new Date();
   		}
		org.bson.Document mongoDoc = cln.find(eq(_id, oid)).first();
		// can it be null?
			
		String content;
		String dataFormat;
		if (toJson) {
			content = mongoDoc.toJson(new JsonWriterSettings(indent));
			dataFormat = df_json; 
		} else {
			content = XMLUtils.mapToXML(mongoDoc);
			dataFormat = repo.getSchema().getProperty(pn_schema_format_default);
		}

		String owner = "admin";
   		try {
   	   		DocumentManagement docManager = (DocumentManagement) repo.getDocumentManagement();
   	   		// how can we get doc's owner?
   			return docManager.createDocument(key, mParts[0], content, dataFormat, creDate, owner, TX_INIT, clns, true);
		} catch (BagriException ex) {
			logger.error("loadDocument.error", ex);
			// TODO: notify popManager about this?!
		}
		return null;
	}
    	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void storeAllDocuments(Map<DocumentKey, Document> entries) {
		logger.debug("storeAllDocuments.enter; got {} entries", entries.size());
		// there is no Mongo API to replace many documents in one operation, unfortunately
		// sort docs by key, version in order to get remove/create sequence..
    	SchemaRepository repo = getRepository();
		int cnt = 0;
		int err = 0;
		Exception ex = null;
    	for (Map.Entry<DocumentKey, Document> entry: entries.entrySet()) {
    		Exception e = storeDocumentInternal(repo, entry.getKey(), entry.getValue());
			if (e == null) {
				cnt++;
			} else {
				err++;
				ex = e;
			}
    	}
    	if (err > 0) {
			logger.info("storeAllDocuments.exit; stored: {}; errors: {}", cnt, err);
			throw new RuntimeException(ex);
    	}
    	logger.debug("storeAllDocumets.exit; stored: {}", cnt);
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void storeDocument(DocumentKey key, Document value) {
		logger.debug("storeDocument.enter; got key {}, value {}", key, value);
    	Exception ex = storeDocumentInternal(getRepository(), key, value);
		if (ex != null) {
			logger.error("storeDocument.error; exception on store document: ", ex);
			throw new RuntimeException(ex);
		}
    	logger.debug("storeDocument.exit; ");
	}
	
	private Exception storeDocumentInternal(SchemaRepository repo, DocumentKey key, Document value) {
		if (value.getTxFinish() > TX_NO) {
			// document version finished -> delete it!
			deleteDocument(key);
			// what if we have deleted old version and inserted new one? in which order?!
			return null;
		}
		
		DocumentManagement docManager = (DocumentManagement) repo.getDocumentManagement(); 
		String content;
		try {
			content = docManager.getDocumentAsString(key, null);
		} catch (BagriException ex) {
			return ex;
		}
		
		MongoCollection<org.bson.Document> cln;
   		PopulationManagement popManager = repo.getPopulationManagement(); 
    	String keyMap = popManager.getKeyMapping(key);
    	String id;
		if (keyMap == null) {
			// create a new document
			//logger.trace("store; got path: {}; uri: {}", path, uri);
			String clName = getDocumentCollection(value);
			id = value.getUri();
			keyMap = getMappingKey(id, clName);
			popManager.setKeyMapping(key, keyMap);
			if (!clMap.containsKey(clName)) {
				// create new collection!
				// what if race conditions?!
				db.createCollection(clName);
				cln = db.getCollection(clName);
				clMap.put(clName, cln);
			} else {
				cln = clMap.get(clName);
			}
		} else {
			// update existing document
			String[] mParts = getMappingParts(keyMap);
			id = mParts[0];
			cln = clMap.get(mParts[1]);
		}
		
		org.bson.Document mongoDoc;
		if (toJson) {
			mongoDoc = org.bson.Document.parse(content);
			// TODO: get new JSON representation with $oids..
		} else {
			mongoDoc = new org.bson.Document(XMLUtils.mapFromXML(content));
		}
		UpdateResult result = cln.replaceOne(eq(_id, id), mongoDoc, new UpdateOptions().upsert(true));
		return null;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void deleteAllDocuments(Collection<DocumentKey> keys) {
		logger.debug("deleteAllDocuments.enter; got {} keys", keys.size());
		// actually, this method should never be called!
		SchemaRepository repo = getRepository();
   		PopulationManagement popManager = repo.getPopulationManagement(); 
		List<String> docIds = new ArrayList<>(keys.size());
		for (DocumentKey key: keys) {
			String keyMap = popManager.deleteKeyMapping(key);
			if (keyMap != null) {
				docIds.add(getMappingParts(keyMap)[0]);
			}
		}
		long deleted = 0;
		for (MongoCollection<org.bson.Document> cln: clMap.values()) {
			DeleteResult result = cln.deleteMany(in(_id, docIds));
			deleted += result.getDeletedCount();
			if (deleted >= keys.size()) {
				break;
			}
		}
		logger.debug("deleteAllDocuments.exit; deleted: {}", deleted);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void deleteDocument(DocumentKey key) {
		logger.debug("deleteDocument.enter; got key {}", key);
		long deleted = 0;
		SchemaRepository repo = getRepository();
   		PopulationManagement popManager = repo.getPopulationManagement(); 
		String keyMap = popManager.deleteKeyMapping(key);
		if (keyMap != null) {
			String[] mParts = getMappingParts(keyMap);
			MongoCollection<org.bson.Document> cln = clMap.get(mParts[1]);
			DeleteResult result = cln.deleteOne(eq(_id, mParts[0]));
			deleted = result.getDeletedCount();
		}
		logger.debug("deleteDocument.exit; deleted: {}", deleted);
	}
	
	private String getMappingKey(String id, String cln) {
		return id + "::" + cln;
	}
	
	private String[] getMappingParts(String keyMap) {
		return keyMap.split("::");
	}

	private String getDocumentCollection(Document doc) {
		int[] clns = doc.getCollections();
		SchemaRepository repo = getRepository();
		for (int id: clns) {
			for (com.bagri.core.system.Collection cln: repo.getSchema().getCollections()) {
				if (cln.getId() == id) {
					return cln.getName();
				}
			}
		}
		return null;
	}

}
