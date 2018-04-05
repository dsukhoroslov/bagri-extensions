package com.bagri.ext.store.cassandra;

import static com.bagri.core.Constants.pn_document_headers;
import static com.bagri.core.Constants.pn_schema_format_default;
import static com.bagri.core.api.TransactionManagement.TX_INIT;
import static com.bagri.core.api.TransactionManagement.TX_NO;
import static com.bagri.core.system.DataFormat.df_json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bagri.core.DocumentKey;
import com.bagri.core.api.BagriException;
import com.bagri.core.api.DocumentAccessor;
import com.bagri.core.server.api.DocumentManagement;
import com.bagri.core.server.api.DocumentStore;
import com.bagri.core.server.api.PopulationManagement;
import com.bagri.core.server.api.SchemaRepository;
import com.bagri.core.server.api.impl.DocumentStoreBase;
import com.bagri.server.hazelcast.impl.DocumentManagementImpl;
import com.bagri.core.model.Document;
import com.bagri.support.util.XMLUtils;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ColumnMetadata;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.TableMetadata;

public class CassandraStore extends DocumentStoreBase implements DocumentStore {
	
    private static final Logger logger = LoggerFactory.getLogger(CassandraStore.class);

    private Cluster cluster;
    private Session session;
    
    private Map<String, CassandraMetaData> tables = new HashMap<>();
    
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void init(Map<String, Object> context) {
		logger.debug("init.enter; got context: {}", context);
		super.setContext(context);

		String address = (String) context.get("bdb.cassandra.address"); 
		String keyspace = (String) context.get("bdb.cassandra.keyspace"); 
		String tableNames = (String) context.get("bdb.cassandra.tables"); 

		String[] tabs; 
		if (tableNames != null) {
			tabs = tableNames.split(", ");
			if (tabs.length == 1 && "*".equals(tabs[0])) {
				tabs = new String[0];
			}
		} else {
			tabs = new String[0];
		}
		
		cluster = Cluster.builder().addContactPoint(address).build();      
		int cnt = initMetadata(keyspace, tabs);
		logger.debug("init.exit; got {} tables meta", cnt);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void close() {
		cluster.close();
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

		logger.info("loadAllDocumentKeys; partitioner: {}; ring size: {}", cluster.getMetadata().getPartitioner(),
				cluster.getMetadata().getTokenRanges().size());
		session = cluster.connect();

		List<String> errTabs = new ArrayList<>();
		Map<DocumentKey, String> keyMap = new HashMap<>();
		for (String table: tables.keySet()) {
			CassandraMetaData cmd = tables.get(table);
			String stmt = cmd.selectKeys;
			logger.debug("loadAllDocumentKeys; select: {}", stmt);
			
			try {
				ResultSet rs = session.execute(stmt);
				Iterator<Row> itr = rs.iterator();
				int sz = keyMap.size();
				while (itr.hasNext()) {
					Row row = itr.next();
					String id = row.getObject(0).toString();
					DocumentKey key = repo.getFactory().newDocumentKey(id, 0, 1);
					keyMap.put(key, getMappingKey(id, table));
				}
				logger.debug("loadAllDocumentKeys; table: {}; size: {}", table, keyMap.size() - sz);
			} catch (Exception ex) {
				logger.warn("loadAllDocumentKeys; table: {}; error: {}", table, ex.getMessage());
				errTabs.add(table);
			}
		}
		
		for (String table: errTabs) {
			tables.remove(table);
		}

   		PopulationManagement popManager = repo.getPopulationManagement();
   		popManager.setKeyMappings(keyMap);
		logger.info("loadAllDocumentKeys.exit; returning {} keys", keyMap.size());
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
    		logger.info("loadDocumentInternal; no mapping found for key: {}", key);
    		return null;
    	}
    	String[] mParts = getMappingParts(keyMap);
    	CassandraMetaData cmd = tables.get(mParts[1]);
    	if (cmd == null) {
    		logger.info("loadDocumentInternal; no metadata found for key: {}", keyMap);
    		return null;
    	}
		logger.debug("loadDocumentInternal; got key to load: {}; map: {}; meta: {}", key, keyMap, cmd);
    	
    	int[] clns = null;
    	String[] tabParts = mParts[1].split("\\.");
    	com.bagri.core.system.Collection xcl = repo.getSchema().getCollection(tabParts[1]);
    	if (xcl != null) {
    		clns = new int[] {xcl.getId()};
    	}

    	String token = mParts[0];
		ResultSet rs = session.execute(cmd.select, new Long(token));
		Iterator<Row> itr = rs.iterator();
		if (itr.hasNext()) {
			Row row = itr.next();
			String content = row.getString(0);
		    logger.debug("loadDocumentInternal; content: {}", content);
		    
			String owner = "admin";
	   		// how can we get doc's owner and creation date?
			DocumentManagementImpl docManager = (DocumentManagementImpl) repo.getDocumentManagement();
			try {
				return docManager.createDocument(key, token, content, df_json, new Date(), owner, TX_INIT, clns, true);
			} catch (BagriException ex) {
				logger.error("loadDocumentInternal.error", ex);
				// TODO: notify popManager about this?!
			}
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
			Properties props = new Properties();
			props.setProperty(pn_document_headers, String.valueOf(DocumentAccessor.HDR_CONTENT));
			content = docManager.getDocument(key, props).getContent();
			
			// now we should store content in Cassandra?
		} catch (BagriException ex) {
			return ex;
		}
		
   		PopulationManagement popManager = repo.getPopulationManagement(); 
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
		}
		logger.debug("deleteDocument.exit; deleted: {}", deleted);
	}
	
	private int initMetadata(String keyspace, String[] tabNames) {
		logger.info("initMetadata; keyspace: {}; tables: {}", keyspace, tabNames);
		if (tabNames.length > 0) {
			for (String table: tabNames) {
				TableMetadata tabMeta = cluster.getMetadata().getKeyspace(keyspace).getTable(table);
				addMetadata(tabMeta);
			}
		} else {
			Collection<TableMetadata> tabMetas = cluster.getMetadata().getKeyspace(keyspace).getTables();
			for (TableMetadata tabMeta: tabMetas) {
				addMetadata(tabMeta);
			}
		}
		return tables.size();
	}
	
	private void addMetadata(TableMetadata table) {
		logger.debug("addMetadata.enter; table: {}; key: {}", table, table.getPartitionKey());
		List<String> keys = new ArrayList<>(table.getPartitionKey().size());
		for (ColumnMetadata col: table.getPartitionKey()) {
			keys.add(col.getName());
		}
		String tabName = table.getKeyspace().getName() + "." + table.getName();
		tables.put(tabName, new CassandraMetaData(tabName, keys));
		logger.debug("addMetadata.exit; table: {}; keys: {}", table, keys);
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
	
	private class CassandraMetaData {
		
		//private List<String> keys;
		private String select;
		private String selectKeys;
		
		CassandraMetaData(String table, List<String> keys) {
			//this.keys = keys;
			init(table, keys);
		}
		
		private void init(String table, List<String> keys) {
			
			StringBuilder selBuff = new StringBuilder("select JSON * from ");
			selBuff.append(table);
			selBuff.append(" where token("); 
			StringBuilder selKeyBuff = new StringBuilder("select token(");
			int idx = 0;
			for (String key: keys) {
				if (idx > 0) {
					selBuff.append(", ");
					selKeyBuff.append(", ");
				}
				selBuff.append(key);
				selKeyBuff.append(key);
				idx++;
			}
			selBuff.append(") = ?");
			this.select = selBuff.toString();
			selKeyBuff.append(") from ");
			selKeyBuff.append(table);
			this.selectKeys = selKeyBuff.toString();
		}
		
	}

}
