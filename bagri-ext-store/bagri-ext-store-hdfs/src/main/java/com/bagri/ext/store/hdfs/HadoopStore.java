package com.bagri.ext.store.hdfs;

import static com.bagri.core.api.TransactionManagement.TX_INIT;
import static com.bagri.core.Constants.pn_document_headers;
import static com.bagri.core.Constants.pn_schema_format_default;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
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

public class HadoopStore extends DocumentStoreBase implements DocumentStore {
	
    private static final Logger logger = LoggerFactory.getLogger(HadoopStore.class);

    private String hdfsRoot;
    private Configuration config;
	private FileSystem fileSystem;
	
    /**
     * {@inheritDoc}
     */
    @Override
    public void init(Map<String, Object> context) {
    	logger.debug("init.enter; got context: {}", context);
    	super.setContext(context);
    	
    	hdfsRoot = (String) context.get("hadoop.data.path");
    	if (hdfsRoot == null) {
			logger.warn("init; hadoop.data.path property not set, please check schema properties in config.xml"); 
    	}
    	String coreSite = (String) context.get("hadoop.core.site");
		if (coreSite == null) {
			logger.warn("init; hadoop.core.site property not set, please check schema properties in config.xml"); 
		}
    	String hdfsSite = (String) context.get("hadoop.hdfs.site");
		if (hdfsSite == null) {
			logger.warn("init; hadoop.hdfs.site property not set, please check schema properties in config.xml"); 
		}

    	config = new Configuration();
    	config.addResource(new Path(coreSite));
    	config.addResource(new Path(hdfsSite));
        // alternatively provide namenode host and port info
    	//config.set("fs.default.name", hdfsPath);
    	
    	try {
    		fileSystem = FileSystem.get(config);
    	} catch (IOException ex) {
    		logger.error("init.error: ", ex);
    		throw new RuntimeException(ex);
    	}
    	logger.debug("init.exit; initialized config: {}; fileSystem: {}", config, fileSystem);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
    	logger.debug("close");
    	try {
    		fileSystem.close();
    	} catch (IOException ex) {
    		logger.error("close.error: ", ex);
    	}
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

    	Map<DocumentKey, String> keyMap = new HashMap<>();
	    Path root = new Path(hdfsRoot);
		DocumentKey key;
	    try {
		    for (FileStatus fs: fileSystem.listStatus(root)) {
		    	logger.trace("loadAllDocumentKeys; fileStatus: {}", fs);
		    	if (fs.isFile()) {
		    		String uri = fs.getPath().getName();
					int rev = 0;
					do {
						key = repo.getFactory().newDocumentKey(uri, rev, 1);
						if (keyMap.containsKey(key)) {
							rev++;
							logger.debug("loadAllDocumentKeys; the key {} already exists; uri: {}; increased revision number to {}", key, uri, rev);
						} else {
							keyMap.put(key, hdfsRoot + "/" + uri);
							break;
						}
					} while (true);
		    	} else {
		    		// will we process directories recursevely?
		    	}
		    }
	    	PopulationManagement popManager = repo.getPopulationManagement();
	    	popManager.setKeyMappings(keyMap);
	    } catch (IOException ex) {
	    	logger.error("loadAllDocumentKeys.error: ", ex);
	    }
	    
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
    	String uri = popManager.getKeyMapping(key); 
    	if (uri == null) {
    		// not clear, what is it
    		logger.info("loadDocumentInternal; no mapping found for key: {}", key);
    		return null;
    	}

		String dataFormat = repo.getSchema().getProperty(pn_schema_format_default);
		try {
	        Path path = new Path(uri);
	        if (!fileSystem.exists(path)) {
	    		logger.info("loadDocumentInternal; file {} does not exists", uri);
	    		return null;
	        }

	        String content;
	        boolean readAsSequence = false;
	        if (readAsSequence) {
	        	SequenceFile.Reader reader = new SequenceFile.Reader(fileSystem, path, config);
	        	WritableComparable wk = (WritableComparable) reader.getKeyClass().newInstance();
	        	Writable wv = (Writable) reader.getValueClass().newInstance();
	        	while (reader.next(wk, wv)) {
		    		logger.info("loadDocumentInternal; key: {}", wk);
		    		logger.info("loadDocumentInternal; value: {}", wv);
	        	}
	        	content = null; 
	        	reader.close();        	
	        } else {
		        int bsz = 4096;
		        FSDataInputStream in = fileSystem.open(path);
		        ByteArrayOutputStream out = new ByteArrayOutputStream(bsz); 
		        IOUtils.copyBytes(in, out, bsz, false);
		        content = new String(out.toByteArray());
	        	IOUtils.closeStream(in);
	        }
        	
	        FileStatus fs = fileSystem.getFileStatus(path);
			DocumentManagementImpl docManager = (DocumentManagementImpl) repo.getDocumentManagement(); 
			return docManager.createDocument(key, uri, content, dataFormat, new Date(fs.getModificationTime()), 
					fs.getOwner(), TX_INIT, null, true);
		} catch (Exception ex) {
			logger.error("loadDocumentInternal.error", ex);
		}
    	return null;
    }
    	
    /**
     * {@inheritDoc}
     */
    @Override
    public void storeAllDocuments(Map<DocumentKey, Document> entries) {
    	logger.debug("storeAllDocuments.enter; got {} entries", entries.size());
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
    
    private Exception storeDocumentInternal(SchemaRepository repo, DocumentKey key, Document doc) {
   		PopulationManagement popManager = repo.getPopulationManagement(); 
		String uri = popManager.getKeyMapping(key);
		if (uri == null) {
			// create a new document
			//logger.trace("store; got path: {}; uri: {}", path, uri);
			uri = doc.getUri();
			popManager.setKeyMapping(key, uri);
		//} else {
			// update existing document - put a new version
		}
    	
		DocumentManagement docManager = (DocumentManagement) repo.getDocumentManagement(); 
		String content;
		try {
			Properties props = new Properties();
			props.setProperty(pn_document_headers, String.valueOf(DocumentAccessor.HDR_CONTENT));
			content = docManager.getDocument(key, props).getContent();

            FSDataOutputStream out = fileSystem.create(new Path(uri), true);
            out.writeChars(content);
            out.flush();
            out.close();
    	} catch (IOException | BagriException ex) {
    		return ex;
    	}
    	return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteAllDocuments(Collection<DocumentKey> keys) {
    	logger.trace("deleteAllDocuments.enter; got {} keys", keys.size());
    	// actually, this method should never be called!
		int cnt = 0;
		int err = 0;
		Exception ex = null;
    	SchemaRepository repo = getRepository();
    	for (DocumentKey key: keys) {
    		Exception e = deleteDocumentInternal(repo, key);
			if (e == null) {
				cnt++;
			} else {
				err++;
				ex = e;
			}
    	}
    	if (err > 0) {
			logger.info("deleteAllDocuments.exit; deleted: {}; errors: {}", cnt, err);
			throw new RuntimeException(ex);
    	}
    	logger.debug("deleteAllDocuments.exit; deleted: {}", cnt);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteDocument(DocumentKey key) {
    	logger.debug("deleteDocument.enter; got key {}", key);
    	Exception ex = deleteDocumentInternal(getRepository(), key);
		if (ex != null) {
			logger.error("deleteDocument.error; exception on delete document: ", ex);
			throw new RuntimeException(ex);
		}
    	logger.debug("deleteDocument.exit; ");
    }
    
    private Exception deleteDocumentInternal(SchemaRepository repo, DocumentKey key) {
    	PopulationManagement popManager = repo.getPopulationManagement(); 
    	String uri = popManager.deleteKeyMapping(key);
    	if (uri == null) {
	    	logger.warn("deleteDocumentInternal; no mapping found for uri {}", uri);
    	} else {
    		try {
	    		Path path = new Path(uri);
	    		if (!fileSystem.exists(path)) {
	    	    	logger.warn("deleteDocumentInternal; file {} does not exists", uri);
	    		} else if (!fileSystem.delete(path, true)) {
	    	    	logger.warn("deleteDocumentInternal; file {} was not deleted", uri);
	    		}
    		} catch (IOException ex) {
    			return ex;
    		}
    	}
		return null;
    }

    //private String getDocumentCollection(Document doc) {
    //	int[] clns = doc.getCollections();
    //	SchemaRepository repo = getRepository();
    //	for (int id: clns) {
    //		for (com.bagri.xdm.system.Collection cln: repo.getSchema().getCollections()) {
    //			if (cln.getId() == id) {
    //				return cln.getName();
    //			}
    //		}
    //	}
    //	return null;
    //}

}
