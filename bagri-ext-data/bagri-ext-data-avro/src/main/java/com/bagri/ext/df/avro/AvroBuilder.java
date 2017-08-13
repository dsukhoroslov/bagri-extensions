package com.bagri.ext.df.avro;

import static com.bagri.support.util.FileUtils.def_encoding;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.avro.Schema;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.avro.io.DatumWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bagri.core.DataKey;
import com.bagri.core.api.BagriException;
import com.bagri.core.server.api.ContentBuilder;
import com.bagri.core.server.api.ModelManagement;
import com.bagri.core.model.Element;
import com.bagri.core.model.Elements;
import com.bagri.core.model.Path;

public class AvroBuilder implements ContentBuilder {
	
    private static final Logger logger = LoggerFactory.getLogger(AvroBuilder.class);
	
	// looks like we'll need init methods for Parser/Builder!
	
    private ModelManagement model;
	private Schema schema;

	public AvroBuilder(ModelManagement model) {
		//super(model);
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
		} catch (IOException ex) {
			logger.error("init.error", ex);
		} 
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public InputStream buildStream(Map<DataKey, Elements> elements) throws BagriException {
		String content = buildString(elements);
		if (content != null) {
			try {
				return new ByteArrayInputStream(content.getBytes(def_encoding));
			} catch (UnsupportedEncodingException ex) {
				throw new BagriException(ex, BagriException.ecInOut);
			}
		}
		return null;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String buildString(Map<DataKey, Elements> elements) throws BagriException {
		logger.trace("buildString.enter; got elements: {}", elements);
		GenericRecord record = buildRecord(elements.keySet().iterator().next().getDocumentKey(), "", 0, schema, elements);
		logger.trace("buildString; got record: {}", record);
		DatumWriter<GenericRecord> datumWriter = new GenericDatumWriter<GenericRecord>(schema);
		DataFileWriter<GenericRecord> dataFileWriter = new DataFileWriter<GenericRecord>(datumWriter);
		ByteArrayOutputStream baos = new ByteArrayOutputStream(); 
		try {
			dataFileWriter.create(schema, baos);
			dataFileWriter.append(record);
			dataFileWriter.close();
			return new String(baos.toByteArray());
		} catch (IOException ex) {
			logger.error("buildString.error", ex);
			throw new BagriException(ex, BagriException.ecInOut);
		}
	}
	
	private GenericRecord buildRecord(long docKey, String parent, int idx, Schema schema, Map<DataKey, Elements> elements) {
		GenericRecordBuilder builder = new GenericRecordBuilder(schema);
		for (Schema.Field field: schema.getFields()) {
			Object value = null;
			String name = parent + "/" + field.name(); 
			Schema.Type type = field.schema().getType();
			if (type == Schema.Type.BOOLEAN || type == Schema.Type.DOUBLE || type == Schema.Type.FLOAT ||
				type == Schema.Type.INT || type == Schema.Type.LONG || type == Schema.Type.STRING ||
				type == Schema.Type.FIXED || type == Schema.Type.ENUM || type == Schema.Type.BYTES) {
				name = name + "/text()";
				Path path = model.getPath(name);
				if (path == null) {
					continue;
				}
				DataKey key = new DataKey(docKey, path.getPathId());
				Elements data = elements.get(key);
				value = getValue(data.getElements(), idx);
				value = convertValue(field.schema(), value);
			} else if (type == Schema.Type.UNION) {
				name = name + "/text()";
				Path path = model.getPath(name);
				if (path != null) {
					DataKey key = new DataKey(docKey, path.getPathId());
					Elements data = elements.get(key);
					value = getValue(data.getElements(), idx);
				} else {
					value = null;
				}
				int pos = GenericData.get().resolveUnion(field.schema(), value);
				value = convertValue(field.schema().getTypes().get(pos), value);
			} else if (type == Schema.Type.RECORD) {
				value = buildRecord(docKey, name, idx, field.schema(), elements);
			} else if (type == Schema.Type.ARRAY) {
				Path path = model.getPath(name);
				if (path == null) {
					// check if null/default is possible?
					continue;
				}
				DataKey key = new DataKey(docKey, path.getPathId());
				Elements data = elements.get(key);
				List values = new ArrayList();
				if (field.schema().getElementType().getType() == Schema.Type.RECORD) {
					int size = getArraySize(docKey, name, field.schema().getElementType(), elements);
					for (int i=0; i < size; i++) {
						value = buildRecord(docKey, name, i, field.schema().getElementType(), elements);
						values.add(value);
					}
				} else {
					name = name + "/text()"; 
					path = model.getPath(name);
					key = new DataKey(docKey, path.getPathId());
					data = elements.get(key);
					int[] bounds = getArrayBounds(idx, data);
					//logger.trace("buildRecord; schema: {}; data: {}; idx: {}; size: {}", field.schema(), data, idx, size);
					for (int i=bounds[0]; i <= bounds[1]; i++) {
						value = getValue(data.getElements(), i);
						value = convertValue(field.schema(), value);
						values.add(value);
					}
				}
				value = values;
			} else if (type == Schema.Type.MAP) {
				Path path = model.getPath(name);
				if (path == null) {
					// check if null/default is possible?
					continue;
				}
				Map<String, Object> values = new HashMap<>();
				DataKey key = new DataKey(docKey, path.getPathId());
				Elements data = elements.get(key);
				// usually it should be only one element
				for (Element elt: data.getElements()) {
					Map<DataKey, Elements> children = getChildern(key, data, elements);
					for (Map.Entry<DataKey, Elements> entry: children.entrySet()) {
						path = model.getPath(entry.getKey().getPathId());
						name = path.getName();
						if (field.schema().getValueType().getType() == Schema.Type.RECORD) {
							value = buildRecord(docKey, name, idx, field.schema().getValueType(), elements);
							values.put(name, value);
						} else {
							String nm = path.getPath() + "/text()"; 
							path = model.getPath(nm);
							logger.trace("buildRecord; nm: {}; path: {}", nm, path);
							key = new DataKey(docKey, path.getPathId());
							data = elements.get(key);
							value = getValue(data.getElements(), idx);
							value = convertValue(field.schema().getValueType(), value);
							values.put(name, value);
						}
						// what about other types?!
					}
				}
				value = values;
			} // what about Schema.Type.NULL ?
			builder.set(field, value);
		}
		return builder.build();
	}
	
	private Map<DataKey, Elements> getChildern(DataKey key, Elements parent, Map<DataKey, Elements> elements) {
		Map<DataKey, Elements> result = new HashMap<>();
		int parentId = parent.getElements().iterator().next().getElementId();
		for (Map.Entry<DataKey, Elements> entry: elements.entrySet()) {
			if (entry.getValue().getElements().iterator().next().getParentId() == parentId) {
				result.put(entry.getKey(), entry.getValue());
			}
		}
		return result;
	}
	
	private int getArraySize(long docKey, String parent, Schema schema, Map<DataKey, Elements> elements) {
		Schema.Field field = schema.getFields().get(0);
		String name = parent + "/" + field.name(); 
		Path path = model.getPath(name);
		logger.trace("getArraySize; name: {}; path: {}", name, path);
		DataKey key = new DataKey(docKey, path.getPathId());
		Elements data = elements.get(key);
		return data.getElements().size();
	}

	private int[] getArrayBounds(int index, Elements elements) {
		int pid = -1; int pos = 0; int idx = 0;
		List<List<Integer>> buckets = new ArrayList<>();
		for (Element element: elements.getElements()) {
			List<Integer> bucket;
			if (element.getParentId() == pid) {
				bucket = buckets.get(buckets.size() - 1);
			} else {
				bucket = new ArrayList<>();
				buckets.add(bucket);
				pid = element.getParentId();
			}
			bucket.add(idx);
			idx++;
		}
		List<Integer> current = buckets.get(index);
		return new int[] {current.get(0), current.get(current.size() - 1)};
	}
	
	private Object getValue(Collection<Element> elements, int idx) {
		if (elements == null) {
			return null;
		}
		if (elements.size() == 1) {
			return elements.iterator().next().getValue();
		}
		//Object[] value = new Object[elements.size()];
		int i = 0;
		for (Element element: elements) {
			if (i == idx) {
				return element.getValue();
			}
			i++;
		}
		return null;
	}
	
	private Object convertValue(Schema schema, Object value) {
		if (value == null) {
			return null;
		}
		logger.trace("convertValue; schema: {}, value: {}", schema, value); 
		switch (schema.getType()) {
			case BOOLEAN: return new Boolean((String) value);
			case BYTES: return ((String) value).getBytes();
			case DOUBLE: return new Double((String) value);
			case ENUM: return GenericData.get().createEnum((String) value, schema);
			case FIXED: return GenericData.get().createFixed(null, ((String) value).getBytes(), schema);
			case FLOAT: return new Float((String) value);
			case INT: return new Integer((String) value);
			case LONG: return new Long((String) value);
			case NULL: return null;
			case STRING: return (String) value;
			case ARRAY:  
			case MAP:
			case RECORD:
			case UNION:
		}
		return value;
	}
	
}
