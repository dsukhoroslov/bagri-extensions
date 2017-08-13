package com.bagri.ext.store.jdbc;

import static com.bagri.core.Constants.pn_schema_format_default;
import static com.bagri.core.api.TransactionManagement.TX_INIT;
import static com.bagri.core.api.TransactionManagement.TX_NO;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.commons.dbcp2.BasicDataSourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import com.bagri.core.DocumentKey;
import com.bagri.core.api.BagriException;
import com.bagri.core.server.api.DocumentManagement;
import com.bagri.core.server.api.DocumentStore;
import com.bagri.core.server.api.PopulationManagement;
import com.bagri.core.server.api.SchemaRepository;
import com.bagri.core.server.api.impl.DocumentStoreBase;
import com.bagri.core.model.Document;
import com.bagri.support.util.XMLUtils;

public class JdbcStore extends DocumentStoreBase implements DocumentStore {
	
    private static final Logger logger = LoggerFactory.getLogger(JdbcStore.class);

    private static String dilimeter = "::"; 
    private static String multiParams = "multi_params"; 

    private String dataFormat;
    private Connection connect;
	private BasicDataSource dataSource;
	private NamedParameterJdbcTemplate jdbc;
	
	private Map<String, String> tableSelect = new HashMap<>();
	private Map<String, String> tableInsert = new HashMap<>();
	private Map<String, String> tableUpdate = new HashMap<>();
	private Map<String, String> tableDelete = new HashMap<>();
	private Map<String, List<String>> tableKeys = new HashMap<>();
	private Map<String, Map<String, ColumnData>> tableCols = new HashMap<>();
    
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void init(Map<String, Object> context) {
		logger.debug("init.enter; got context: {}", context);
		super.setContext(context);

		Properties props = convertToProps(context);
		try {
			dataSource = BasicDataSourceFactory.createDataSource(props);
			connect = dataSource.getConnection();
		} catch (Exception ex) {
    		logger.error("init.error: ", ex);
    		throw new RuntimeException(ex);
		}
		
		jdbc = new NamedParameterJdbcTemplate(dataSource);
		String tabs = (String) context.get("xdm.jdbc.tables");
		String[] ta = new String[0]; 
		if (tabs != null) {
			ta = tabs.split(",");
		}
		if (ta.length == 0) {
			ta = new String[] {null};
		}
		
		int cnt = initMetadata(ta);
		logger.debug("init.exit; got {} tables meta", cnt);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void close() {
		try {
			connect.close();
			dataSource.close();
		} catch (SQLException ex) {
    		logger.error("close.error: ", ex);
		}
		jdbc = null;
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
		dataFormat = repo.getSchema().getProperty(pn_schema_format_default);

	    Map<DocumentKey, String> keyMap = new HashMap<>();
    	List<String> tables = new ArrayList<>(tableKeys.keySet());
    	for (String tabName: tables) {
	    	int idx = 0;
	    	List<String> pkCols = tableKeys.get(tabName);
	    	StringBuffer query = new StringBuffer("select ");
	    	for (String col: pkCols) {
	    		if (idx > 0) {
	    			query.append(", ");
	    		}
	    		query.append(col);
	    		idx++;
	    	}
	    	query.append(" from ").append(tabName);
	    	String select = query.toString();
			logger.trace("loadAllDocumentKeys; going to run query: {} for table {}", select, tabName);
			int sz = keyMap.size();
			try {
				jdbc.query(select, new KeyCallbackHandler(tabName, pkCols, keyMap));
				logger.debug("loadAllDocumentKeys; loaded {} keys for table {}", keyMap.size() - sz, tabName);
			} catch (Exception ex) {
				logger.info("loadAllDocumentKeys; got error fetching keys for table {}: {}; removing table from the fetch list", tabName, ex.getMessage());
				tableKeys.remove(tabName);
			}
	    }
	    
   		PopulationManagement popManager = repo.getPopulationManagement();
   		popManager.setKeyMappings(keyMap);
		logger.debug("loadAllDocumentKeys.exit; returning {} keys for {} tables", keyMap.size(), tableKeys.size());
		return keyMap.keySet();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Map<DocumentKey, Document> loadAllDocuments(Collection<DocumentKey> keys) {
		logger.debug("loadAllDocuments.enter; got {} keys", keys.size());
   		Map<String, Map<DocumentKey, String>> tabMap = groupByTableName(getRepository(), keys);
   		logger.trace("loadAllDocuments; got groups: {}", tabMap);
		Map<DocumentKey, Document> result = new HashMap<>(keys.size());
   		for (Map.Entry<String, Map<DocumentKey, String>> e: tabMap.entrySet()) {
   	    	String tabName = e.getKey();
   	    	String select = tableSelect.get(tabName); 
   			jdbc.query(select, new DocumentKeyParameterSource(tableKeys.get(tabName), e.getValue().values(), tableCols.get(tabName)), 
   					new DocCallbackHandler(tabName, tableKeys.get(tabName), e.getValue(), result));
   		}
		logger.debug("loadAllDocuments.exit; returning {} entries", result.size());
		return result;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Document loadDocument(DocumentKey key) {
		logger.debug("loadDocument.enter; got key {}", key);
		SchemaRepository repo = getRepository();
		PopulationManagement popManager = repo.getPopulationManagement(); 
    	String uri = popManager.getKeyMapping(key); 
    	if (uri == null) {
    		// not clear, what is it
    		logger.info("loadDocument; no mapping found for key: {}", key);
    		return null;
    	}

    	String tabName = getTableName(uri);
    	String select = tableSelect.get(tabName); 
    	Map<DocumentKey, String> keyMap = new HashMap<>(1);
    	keyMap.put(key, uri);
		Map<DocumentKey, Document> result = new HashMap<>(1);
		jdbc.query(select, new DocumentKeyParameterSource(tableKeys.get(tabName), keyMap.values(), tableCols.get(tabName)), 
				new DocCallbackHandler(tabName, tableKeys.get(tabName), keyMap, result));
		Document doc = result.get(key);
		logger.debug("loadDocument.exit; returning document {}", doc);
		return doc;
	}
    	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void storeAllDocuments(Map<DocumentKey, Document> entries) {
		logger.debug("storeAllDocuments.enter; got {} entries", entries.size());
		int updated = 0;
		// sort docs by key, version in order to get remove/create sequence..
    	SchemaRepository repo = getRepository();
   		Map<String, Map<DocumentKey, String>> tabMap = groupByTableName(repo, entries.keySet());
   		logger.trace("storeAllDocuments; got groups: {}", tabMap);
   		// actually, all updated documents will get a new version, so no mapping will be found for them!
   		
   		PopulationManagement popManager = repo.getPopulationManagement(); 
   		for (Map.Entry<String, Map<DocumentKey, String>> e: tabMap.entrySet()) {
   	    	String tabName = e.getKey();
   	    	Map<DocumentKey, String> tabKeys = e.getValue();
   	    	String update = tableUpdate.get(tabName); 
   			//int[] cnts = jdbc.batchUpdate(update, new SqlParameterSource[] {new DocParameterSource(tableKeys.get(tabName), tabKeys.values(), tableCols.get(tabName))});
			
   			//int cnt = popManager.setKeyMappings(tabKeys.keySet());
   			
   			//updated += cnts[0]; 
			//logger.trace("storeAllDocuments; deleted by jdbs: {}; deleted keys: {}; for table: {}", cnts[0], cnt, tabName);
		}
    	logger.debug("storeAllDocumets.exit; stored: {}", updated);
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void storeDocument(DocumentKey key, Document value) {
		logger.debug("storeDocument.enter; got key {}, value {}", key, value);
		if (value.getTxFinish() > TX_NO) {
			// document version finished -> delete it!
			deleteDocument(key);
			// what if we have deleted old version and inserted new one? in which order?!
			return;
		}
		
		SchemaRepository repo = getRepository();
		DocumentManagement docManager = (DocumentManagement) repo.getDocumentManagement(); 
		Map<String, Object> content;
		try {
			content = docManager.getDocumentAsMap(key, null);
		} catch (BagriException ex) {
			logger.error("storeDocument.error;", ex);
			throw new RuntimeException(ex);
		}

		int inserted = 0;
		int updated = 0;
   		PopulationManagement popManager = repo.getPopulationManagement(); 
    	String uri = popManager.getKeyMapping(key);
		if (uri == null) {
			// create a new document
			// to which table should we insert it? to its Collection -> table
			int[] clns = value.getCollections();
			if (clns.length == 0) {
				logger.warn("storeDocument; no Collection specified in {}; thus cannot derive Table to insert document into, skipping it.", value);
			} else if (clns.length  > 1) {
				logger.warn("storeDocument; too many Collections specified in {}; don't know to which Table insert document, skipping it.", value);
			} else {
				String tabName = getCollectionName(repo, clns[0]);
				if (tableCols.containsKey(tabName)) {
					uri = buildUri(tabName, content);
					popManager.setKeyMapping(key, uri);
			    	String insert = tableInsert.get(tabName); 
			    	Map<DocumentKey, String> keyMap = new HashMap<>(1);
			    	keyMap.put(key, uri);
					int[] cnts = jdbc.batchUpdate(insert, new SqlParameterSource[] {new DocumentParameterSource(tableKeys.get(tabName), keyMap.values(), tableCols.get(tabName), content)});
					inserted += cnts[0];
					// handle concurrent insert here!
				} else {
					logger.warn("storeDocument; unknown table {} specified, skipping document persistence", tabName);
				}
			}
		} else {
			// update existing document
	    	String tabName = getTableName(uri);
	    	String update = tableUpdate.get(tabName); 
	    	Map<DocumentKey, String> keyMap = new HashMap<>(1);
	    	keyMap.put(key, uri);
			int[] cnts = jdbc.batchUpdate(update, new SqlParameterSource[] {new DocumentParameterSource(tableKeys.get(tabName), keyMap.values(), tableCols.get(tabName), content)});
			updated += cnts[0];
		}
    	logger.debug("storeDocument.exit; inserted: {}; updated: {}", inserted, updated);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void deleteAllDocuments(Collection<DocumentKey> keys) {
		logger.debug("deleteAllDocuments.enter; got {} keys", keys.size());
		long deleted = 0;
		// actually, this method should never be called!
		SchemaRepository repo = getRepository();
   		Map<String, Map<DocumentKey, String>> tabMap = groupByTableName(repo, keys);
   		logger.trace("loadAllDocuments; got groups: {}", tabMap);
   		PopulationManagement popManager = repo.getPopulationManagement(); 
   		for (Map.Entry<String, Map<DocumentKey, String>> e: tabMap.entrySet()) {
   	    	String tabName = e.getKey();
   	    	Map<DocumentKey, String> tabKeys = e.getValue();
   	    	String delete = tableDelete.get(tabName); 
   			int[] cnts = jdbc.batchUpdate(delete, new SqlParameterSource[] {new DocumentKeyParameterSource(tableKeys.get(tabName), tabKeys.values(), tableCols.get(tabName))});
			int cnt = popManager.deleteKeyMappings(tabKeys.keySet());
   			deleted += cnts[0]; 
			logger.trace("deleteAllDocuments; deleted by jdbs: {}; deleted keys: {}; for table: {}", cnts[0], cnt, tabName);
		}
		logger.debug("deleteAllDocuments.exit; deleted: {}", deleted);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void deleteDocument(DocumentKey key) {
		logger.debug("deleteDocument.enter; got key {}", key);
		SchemaRepository repo = getRepository();
		PopulationManagement popManager = repo.getPopulationManagement(); 
		String uri = popManager.deleteKeyMapping(key);
    	if (uri == null) {
    		// not clear, what is it
    		logger.info("deleteDocument; no mapping found for key: {}", key);
    		return;
    	}

    	String tabName = getTableName(uri);
    	String delete = tableDelete.get(tabName); 
    	Map<DocumentKey, String> keyMap = new HashMap<>(1);
    	keyMap.put(key, uri);
		int[] cnts = jdbc.batchUpdate(delete, new SqlParameterSource[] {new DocumentKeyParameterSource(tableKeys.get(tabName), keyMap.values(), tableCols.get(tabName))});
		logger.debug("deleteDocument.exit; deleted: {}", cnts[0]);
	}
	
	private int initMetadata(String[] tables) {
		logger.debug("initMetadata; got table patterns {}", Arrays.toString(tables));
		try {
			for (String tabName: tables) {
				logger.trace("initMetadata; loading table data for table pattern: {}", tabName);
				DatabaseMetaData meta = connect.getMetaData();
				ResultSet rs = meta.getTables(null, null, tabName, null);
				while (rs.next()) {
					tableKeys.put(rs.getString(3), new ArrayList<String>());
				}
				rs.close();
			}
			logger.debug("initMetadata; going to load metadata for tables {}", tableKeys.keySet());

			DatabaseMetaData meta = connect.getMetaData();
	    	List<String> tabNames = new ArrayList<>(tableKeys.keySet());
	    	for (String tabName: tabNames) {
		    	List<String> pkCols = tableKeys.get(tabName);
				ResultSet rs = meta.getPrimaryKeys(null, null, tabName);
				while (rs.next()) {
					String colName = rs.getString(4);
					short colIdx = rs.getShort(5);
					while (colIdx > pkCols.size()) {
						pkCols.add(null);
					}
					pkCols.set(colIdx - 1, colName);
				}
				rs.close();
				logger.trace("initMetadata; got keys: {}, for table: {}", pkCols, tabName);
				boolean empty = pkCols.size() == 0;
				Map<String, ColumnData> allCols = new HashMap<>();

				int idx = 0;
				StringBuffer bufSelect = new StringBuffer("select ");
				StringBuffer bufInsert = new StringBuffer("insert into ");
				StringBuffer bufInsertVal = new StringBuffer(" values(");
				StringBuffer bufUpdate = new StringBuffer("update ");
				bufInsert.append(tabName).append("(");
				bufUpdate.append(tabName).append(" set ");
				rs = meta.getColumns(null, null, tabName, null);
				while (rs.next()) {
					if (idx > 0) {
						bufSelect.append(", ");
						bufInsert.append(", ");
						bufInsertVal.append(", ");
						bufUpdate.append(", ");
					}
					String colName = rs.getString(4);
					// use data_type as well!
					bufSelect.append(colName);
					bufInsert.append(colName);
					bufInsertVal.append(":").append(colName);
					bufUpdate.append(colName).append(" = :").append(colName);
					// add column data here!
					if (empty) {
						pkCols.add(colName);
					}
					idx++;
					allCols.put(colName, new ColumnData(rs.getInt(17), rs.getInt(5), 
							rs.getString(6), "YES".equals(rs.getString(18))));
				}
				rs.close();
				if (pkCols.size() == 0) {
					tableKeys.remove(tabName);
				} else {
					bufSelect.append(" from ").append(tabName).append(" where ");
					StringBuffer bufDelete = new StringBuffer("delete from ");
					bufDelete.append(tabName).append(" where ");
					bufUpdate.append(" where ");
					if (pkCols.size() == 1) {
						bufSelect.append(pkCols.get(0));
						bufSelect.append(" in (:");
						bufSelect.append(pkCols.get(0));
						bufDelete.append(pkCols.get(0));
						bufDelete.append(" in (:");
						bufDelete.append(pkCols.get(0));
						bufUpdate.append(pkCols.get(0));
						bufUpdate.append(" in (:");
						bufUpdate.append(pkCols.get(0));
					} else {
						idx = 0;
						bufSelect.append("(");
						bufDelete.append("(");
						bufUpdate.append("(");
						for (String colName: pkCols) {
							if (idx > 0) {
								bufSelect.append(", ");
								bufDelete.append(", ");
								bufUpdate.append(", ");
							}
							bufSelect.append(colName);
							bufDelete.append(colName);
							bufUpdate.append(colName);
							idx++;
						}
						bufSelect.append(") in (:");
						bufSelect.append(multiParams);
						bufDelete.append(") in (:");
						bufDelete.append(multiParams);
						bufUpdate.append(") in (:");
						bufUpdate.append(multiParams);
					}
					bufSelect.append(")");
					bufInsert.append(")");
					bufInsertVal.append(")");
					bufDelete.append(")");
					bufUpdate.append(")");
					bufInsert.append(bufInsertVal);
					tableCols.put(tabName, allCols);
					logger.debug("initMetadata; got keys: {}; for table: {}", pkCols, tabName);
					String select = bufSelect.toString();
					tableSelect.put(tabName, select);
					logger.debug("initMetadata; got select: {}, for table: {}", select, tabName);
					String insert = bufInsert.toString();
					tableInsert.put(tabName, insert);
					logger.debug("initMetadata; got insert: {}, for table: {}", insert, tabName);
					String delete = bufDelete.toString();
					tableDelete.put(tabName, delete);
					logger.debug("initMetadata; got delete: {}, for table: {}", delete, tabName);
					String update = bufUpdate.toString();
					tableUpdate.put(tabName, update);
					logger.debug("initMetadata; got update: {}, for table: {}", update, tabName);
				}				
	    	}
			logger.debug("initMetadata.exit; got meta for tables {}", tabNames);
			return tabNames.size();
		} catch (SQLException ex) {
    		logger.error("initMetadata.error loading meta: ", ex);
    		throw new RuntimeException(ex);
		}
	}

	private String buildUri(String tableName, Map<String, Object> document) { 
		StringBuffer buff = new StringBuffer(tableName);
		for (String key: tableKeys.get(tableName)) {
			Object value = document.get(key);
			if (value != null) {
				buff.append(dilimeter);
				buff.append(value.toString());
			} else {
				// what else??
			}
		}
		return buff.toString();
	}

	private Properties convertToProps(Map<String, Object> context) {
		Properties props = new Properties();
		for (Map.Entry<String, Object> e: context.entrySet()) {
			if (e.getKey().startsWith("jdbc.")) {
				props.put(e.getKey().substring(5), e.getValue());
			}
		}
		return props;
	}
	
	private String getCollectionName(SchemaRepository repo, int clnId) {
		for (com.bagri.core.system.Collection cln: repo.getSchema().getCollections()) {
			if (cln.getId() == clnId) {
				return cln.getName();
			}
		}
		return null;
	}
	
	private String getTableName(String uri) {
		int pos = uri.indexOf(dilimeter);
		return uri.substring(0, pos);
	}
	
	private Map<String, Map<DocumentKey, String>> groupByTableName(SchemaRepository repo, Collection<DocumentKey> keys) {
		Set<DocumentKey> dKeys;
		if (keys instanceof Set) {
			dKeys = (Set<DocumentKey>) keys;
		} else {
			dKeys = new HashSet<>(keys);
		}
   		PopulationManagement popManager = repo.getPopulationManagement();
   		Map<DocumentKey, String> keyMap = popManager.getKeyMappings(dKeys);
		Map<String, Map<DocumentKey, String>> groups = new HashMap<>();
		for (Map.Entry<DocumentKey, String> e: keyMap.entrySet()) {
			String tabName = getTableName(e.getValue());
			Map<DocumentKey, String> tabMaps = groups.get(tabName);
			if (tabMaps == null) {
				tabMaps = new HashMap<>();
				groups.put(tabName, tabMaps);
			}
			tabMaps.put(e.getKey(), e.getValue());
		}
		return groups;
	}
	

	private static class ColumnData {
		
		private int index;
		private int dataType;
		private String typeName;
		private boolean nullable;
		
		public ColumnData(int index, int type, String name, boolean nullable) {
			this.index = index;
			this.dataType = type;
			this.typeName = name;
			this.nullable = nullable;
		}

		/**
		 * @return the index
		 */
		public int getIndex() {
			return index;
		}

		/**
		 * @return the dataType
		 */
		public int getDataType() {
			return dataType;
		}

		/**
		 * @return the typeName
		 */
		public String getTypeName() {
			return typeName;
		}
		
		public boolean isNullable() {
			return nullable;
		}
	}

	
	private class KeyCallbackHandler implements RowCallbackHandler {
		
		protected String tableName;
		protected List<String> keyCols;
		protected Map<DocumentKey, String> keyMap;
		protected SchemaRepository repo;
		
		public KeyCallbackHandler(String tableName, List<String> keyCols, Map<DocumentKey, String> keyMap) {
			this.tableName = tableName;
			this.keyCols = keyCols;
			this.keyMap = keyMap;
			repo = getRepository();
		}

		@Override
		public void processRow(ResultSet rs) throws SQLException {
			
			int rev = 0;
			DocumentKey key;
			String keyUri = buildUri(rs);  
			do {
				key = repo.getFactory().newDocumentKey(keyUri, rev, 1);
				if (keyMap.containsKey(key)) {
					rev++;
					logger.debug("processRow; the key {} already exists; uri: {}; increased revision number to {}", key, keyUri, rev);
				} else {
					keyMap.put(key, keyUri);
					break;
				}
			} while (true);
		}
		
		protected String buildUri(ResultSet rs) throws SQLException {
			StringBuffer buff = new StringBuffer(tableName);
			for (String col: keyCols) {
				buff.append(dilimeter);
				buff.append(rs.getString(col));
			}
			return buff.toString();
		}
		
	}
	
	private class DocCallbackHandler extends KeyCallbackHandler {

		private Map<DocumentKey, Document> entries;
		
		DocCallbackHandler(String tableName, List<String> keyCols, Map<DocumentKey, String> keyMap, Map<DocumentKey, Document> entries) {
			super(tableName, keyCols, keyMap);
			this.entries = entries;
		}

		@Override
		public void processRow(ResultSet rs) throws SQLException {
			
			Map<String, Object> docMap = new HashMap<>();
			// TODO: get doc data via collected cols!
			ResultSetMetaData meta = rs.getMetaData();
			for (int i=0; i < meta.getColumnCount(); i++) {
				String name = meta.getColumnLabel(i+1);
				String value = rs.getString(name);
				docMap.put(name, value);
			}
			String uri = buildUri(rs);
			DocumentKey key = getDocumentKey(uri);

			int[] clns = null;
			String content = XMLUtils.mapToXML(docMap);
			com.bagri.core.system.Collection cln = repo.getSchema().getCollection(tableName);
			if (cln != null) {
				clns = new int[] {cln.getId()};
			}
			String owner = dataSource.getUsername();
			// can we get data of document creation somehow??
			Date creDate = new Date();
	   		try {
	   	   		DocumentManagement docManager = (DocumentManagement) repo.getDocumentManagement();
	   	   		Document doc = docManager.createDocument(key, uri, content, dataFormat, creDate, owner, TX_INIT, clns, true); 
		   		entries.put(key, doc);
			} catch (BagriException ex) {
				logger.error("loadDocument.error", ex);
				// TODO: notify popManager about this?!
			}
		}
		
		private DocumentKey getDocumentKey(String uri) {
			for (Map.Entry<DocumentKey, String> e: keyMap.entrySet()) {
				if (uri.equals(e.getValue())) {
					return e.getKey();
				}
			}
			return null;
		}
		
	}
	
	private class DocumentKeyParameterSource implements SqlParameterSource {

		protected List<String> keys;
		protected Collection<String> uris;
		protected Map<String, ColumnData> columns;
		
		DocumentKeyParameterSource(List<String> keys, Collection<String> uris, Map<String, ColumnData> columns) {
			this.keys = keys;
			this.uris = uris;
			this.columns = columns;
		}

		@Override
		public boolean hasValue(String paramName) {
			if (multiParams.equals(paramName)) {
				return true;
			}
			return columns.containsKey(paramName);
		}

		@Override
		public Object getValue(String paramName) throws IllegalArgumentException {
			// TODO: not sure about String type everywhere
			if (multiParams.equals(paramName)) {
				List<String[]> result = new ArrayList<>();
				Iterator<String> itr = uris.iterator();
				while (itr.hasNext()) {
					List<String> row = new ArrayList<>(keys.size());
					String[] parts = itr.next().split(dilimeter);
					for (String key: keys) {
						ColumnData cd = columns.get(key);
						int idx = cd.getIndex();
						row.add(parts[idx]);
					}
					result.add(row.toArray(new String[row.size()]));
				}
				return result;
			} else {
				if (!columns.containsKey(paramName)) {
					throw new IllegalArgumentException("unknown column: " + paramName);
				}
				ColumnData cd = columns.get(paramName);
				int idx = cd.getIndex();
				List<String> result = new ArrayList<>();
				Iterator<String> itr = uris.iterator();
				while (itr.hasNext()) {
					String[] parts = itr.next().split(dilimeter);
					result.add(parts[idx]);
				}
				return result;
			}
		}

		@Override
		public int getSqlType(String paramName) {
			if (multiParams.equals(paramName)) {
				return java.sql.Types.VARCHAR;
			}
			ColumnData cd = columns.get(paramName);
			return cd.getDataType();
		}

		@Override
		public String getTypeName(String paramName) {
			if (multiParams.equals(paramName)) {
				return "VARCHAR";
			}
			ColumnData cd = columns.get(paramName);
			return cd.getTypeName();
		}

	}
	
	private class DocumentParameterSource extends DocumentKeyParameterSource {
		
		private Map<String, Object> document;

		DocumentParameterSource(List<String> keys, Collection<String> uris, Map<String, ColumnData> columns, Map<String, Object> document) {
			super(keys, uris, columns);
			this.document = document;
		}
		
		@Override
		public Object getValue(String paramName) throws IllegalArgumentException {
			if (multiParams.equals(paramName)) {
				return super.getValue(paramName);
			}
			if (!document.containsKey(paramName)) {
				throw new IllegalArgumentException("unknown column: " + paramName);
			}
			return document.get(paramName);
		}
		
	}
	
}
