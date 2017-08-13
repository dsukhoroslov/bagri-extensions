package com.bagri.ext.df.avro;

import static javax.xml.xquery.XQItemType.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.xquery.XQItemType;

import org.apache.avro.Schema;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.file.DataFileStream;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;

import com.bagri.core.api.BagriException;
import com.bagri.core.server.api.ContentParser;
import com.bagri.core.server.api.ModelManagement;
import com.bagri.core.server.api.impl.ContentParserBase;
import com.bagri.core.model.Data;
import com.bagri.core.model.Element;
import com.bagri.core.model.Path;
import com.bagri.core.model.NodeKind;
import com.bagri.core.model.Occurrence;

public class AvroParser extends ContentParserBase implements ContentParser {
	
    //private static final Logger logger = LoggerFactory.getLogger(AvroParser.class);
	
	private Schema schema;

	public AvroParser(ModelManagement model) {
		super(model);
		this.model = model;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void init(Map<String, Object> context) {
		String sName = (String) context.get("avro.schema.name");
		String[] names = sName.split(" ");
		List<Schema> schemas = new ArrayList<Schema>(names.length);
		try {
			Schema.Parser parser = new Schema.Parser(); 
			for (int i=0; i < names.length; i++) {
				schemas.add(parser.parse(new File(names[i]))); 
			}
			if (schemas.size() > 1) {
				//this.schema = Schema.createUnion(schemas);
				this.schema = schemas.get(schemas.size() - 1);
			} else {
				this.schema = schemas.get(0);
			}
			// process schema here and build dictionaries
			// TODO: refactor ModelManagemnt interface.
			// Schema management should go to Parser, probably!?
		} catch (IOException ex) {
			logger.error("init.error", ex);
		} 
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public List<Data> parse(File file) throws BagriException {
		DatumReader<GenericRecord> datumReader = new GenericDatumReader<GenericRecord>(schema);
		try (DataFileReader<GenericRecord> dataFile = new DataFileReader<GenericRecord>(file, datumReader)) {
			return parse(dataFile);
		} catch (IOException ex) {
			logger.error("parse.error", ex);
			throw new BagriException(ex, BagriException.ecInOut);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public List<Data> parse(InputStream stream) throws BagriException {
		DatumReader<GenericRecord> datumReader = new GenericDatumReader<GenericRecord>(schema);
		try (DataFileStream<GenericRecord> dataStream = new DataFileStream<GenericRecord>(stream, datumReader)) {
			return parse(dataStream);
		} catch (IOException ex) {
			logger.error("parse.error", ex);
			throw new BagriException(ex, BagriException.ecInOut);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public List<Data> parse(String source) throws BagriException {
		// what the source is? JSON or what??
		logger.trace("parse.enter; got source: {}", source); 
		init();
		DatumReader<GenericRecord> datumReader = new GenericDatumReader<GenericRecord>(schema);
		try {
			GenericRecord record = datumReader.read(null, DecoderFactory.get().jsonDecoder(schema, source));
			processDocument(""); //schema.getName()
			Data doc = dataList.get(dataList.size() - 1);
			processRecord(doc, record);
			logger.trace("parse.exit; returning {} elements", dataList); //.size());
			return dataList;
		} catch (IOException ex) {
			logger.error("parse.error", ex);
			throw new BagriException(ex, BagriException.ecInOut);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public List<Data> parse(Reader reader) throws BagriException {
		String line = null;
		init();
		BufferedReader buff = new BufferedReader(reader);
		StringBuilder result = new StringBuilder();
		try  {
			while ((line = buff.readLine()) != null) {
				result.append(line);
			}
			parse(result.toString());
			return dataList;
		} catch (IOException ex) {
			logger.error("parse.error", ex);
			throw new BagriException(ex, BagriException.ecInOut);
		}
	}
	
	private List<Data> parse(DataFileStream<GenericRecord> data) throws IOException, BagriException  {
		init();
		GenericRecord record = null;
		Data doc = dataList.get(dataList.size() - 1);
		while (data.hasNext()) {
			record = data.next(record);
			// TODO: what should we do with several records..?
			processRecord(doc, record);
		}
		return dataList;
	}
	
	private void processRecord(Data parent, GenericRecord record) throws BagriException {
		logger.trace("parse; got record: {}", record);
		for (Schema.Field field: record.getSchema().getFields()) {
			Object data = record.get(field.pos());
			processField(parent, field.schema(), field.name(), data);
		}
	}
	
	private void processField(Data parent, Schema schema, String name, Object data) throws BagriException {
		if (schema.getType() == Schema.Type.ARRAY) {
			//Data arr = addData(parent, NodeKind.element, "/" + name, null, XQBASETYPE_ANYTYPE, Occurrence.zeroOrMany);
			List<Object> values = (List<Object>) data;
			for (Object value: values) {
				//processField(arr, schema.getElementType(), name, value);
				processField(parent, schema.getElementType(), name, value);
			}
		} else if (schema.getType() == Schema.Type.MAP) {
			Data map = addData(parent, NodeKind.element, "/" + name, null, XQBASETYPE_ANYTYPE, Occurrence.onlyOne);
			Map<Object, Object> values = (Map<Object, Object>) data;
			for (Map.Entry<Object, Object> entry: values.entrySet()) {
				processField(map, schema.getValueType(), entry.getKey().toString(), entry.getValue());
			}
		} else if (schema.getType() == Schema.Type.RECORD) {
			Data elt = addData(parent, NodeKind.element, "/" + name, null, XQBASETYPE_ANYTYPE, Occurrence.onlyOne);
			processRecord(elt, (GenericRecord) data);
		} else if (schema.getType() == Schema.Type.UNION) {
			int pos = GenericData.get().resolveUnion(schema, data);
			processField(parent, schema.getTypes().get(pos), name, data);
		} else {
			processValue(parent, name, schema, Occurrence.onlyOne, data);
		}
	}
	
	private Data processValue(Data parent, String name, Schema schema, Occurrence occ, Object value) throws BagriException {
		Data elt = addData(parent, NodeKind.element, "/" + name, null, XQBASETYPE_ANYTYPE, occ);
		int dataType = getFieldType(schema);
		return addData(elt, NodeKind.text, "/text()", value == null ? null : value.toString(), dataType, occ);
	}
	
	private int getFieldType(Schema schema) {
		switch (schema.getType()) {
			case BOOLEAN: return XQBASETYPE_BOOLEAN; 
			case BYTES: return XQBASETYPE_BASE64BINARY;
			case DOUBLE: return XQBASETYPE_DOUBLE;
			case ENUM: return XQBASETYPE_STRING;
			case FIXED: return XQBASETYPE_BASE64BINARY;
			case FLOAT: return XQBASETYPE_FLOAT;
			case INT: return XQBASETYPE_INT;
			case LONG: return XQBASETYPE_LONG;
			case STRING: return XQBASETYPE_STRING;
			case ARRAY: return getFieldType(schema.getElementType()); 
			case MAP: return getFieldType(schema.getValueType());
			case NULL: 
			case RECORD: 
			case UNION:
		}
		return XQBASETYPE_ANYTYPE;
	}
		
	private void processDocument(String name) throws BagriException {

		String root = "/" + (name == null ? "" : name);
		docType = model.translateDocumentType(root);
		Path path = model.translatePath(docType, "", NodeKind.document, XQItemType.XQBASETYPE_ANYTYPE, Occurrence.onlyOne);
		Element start = new Element();
		start.setElementId(elementId++);
		Data data = new Data(path, start);
		dataStack.add(data);
		dataList.add(data);
	}
	
}
