package com.bagri.ext.df.avro;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import com.bagri.core.DataKey;
import com.bagri.core.server.api.ModelManagement;
import com.bagri.core.server.api.impl.ModelManagementImpl;
import com.bagri.core.server.api.df.json.JsonApiParser;
import com.bagri.core.model.Data;
import com.bagri.core.model.Elements;


public class AvroBuilderTest {
	
	private static String json_simple = "{\"username\": \"miguno\", \"tweet\": \"Rock: Nerf paper, scissors is fine.\", \"timestamp\": 1366150681}";
	private static String json_complex = "{\"order_id\": 1000, \"customer_id\": 2000, \"total\": 3000.55, \"order_details\": ["
			+ "{\"quantity\": 10, \"total\": 25.56, \"product_detail\": {"
			+ "\"product_id\": 4000, \"product_name\": \"cucumber\", \"product_description\": null, \"product_status\": \"AVAILABLE\", "
			+ "\"product_category\": [\"VEGS\"], \"price\": 2.64, \"product_hash\": \"\u00FFu012\"}},"
			+ "{\"quantity\": 15, \"total\": 43.98, \"product_detail\": {"
			//+ "\"product_id\": 5000, \"product_name\": \"apple\", \"product_description\": {\"string\": \"Golden\"}, \"product_status\": \"OUT_OF_STOCK\", "
			+ "\"product_id\": 5000, \"product_name\": \"apple\", \"product_description\": \"Golden\", \"product_status\": \"OUT_OF_STOCK\", "
			+ "\"product_category\": [\"FRUTS\", \"APPLES\"], \"price\": 1.345, \"product_hash\": \"\uFF00uEE0\"}}"
			+ "], \"product_map\": {\"cucumber\": 26.40, \"apple\": 18.85}}";
	
	// TODO: test Avro Map structure..
	
	private AvroBuilder builder;
	private ModelManagement model;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		System.setProperty("logback.configurationFile", "test-logging.xml");
	}
	
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}

	@Before
	public void setUp() throws Exception {
		model = new ModelManagementImpl();
		builder = new AvroBuilder(model);
	}

	@After
	public void tearDown() throws Exception {
	}

	@Test
	public void buildStreamTest() throws Exception {
		//
		Map<String, Object> props = new HashMap<>();
		props.put("avro.schema.name", "src/test/resources/twitter.avsc");
		builder.init(props);
		InputStream result = builder.buildStream(getElementsFromJson(json_simple));
		assertNotNull(result);
		assertTrue(result.available() > 0); 
	}

	@Test
	public void buildSimpleStringTest() throws Exception {
		//
		Map<String, Object> props = new HashMap<>();
		props.put("avro.schema.name", "src/test/resources/twitter.avsc");
		builder.init(props);
		String result = builder.buildString(getElementsFromJson(json_simple));
		assertNotNull(result);
		assertTrue(result.length() > 0);
		assertTrue(result.startsWith("Obj"));
	}
	
	@Test
	public void buildComplexStringTest() throws Exception {
		Map<String, Object> props = new HashMap<>();
		props.put("avro.schema.name", "src/test/resources/order_complex.avsc");
		builder.init(props);
		String result = builder.buildString(getElementsFromJson(json_complex));
		assertNotNull(result);
		assertTrue(result.length() > 0);
		assertTrue(result.startsWith("Obj"));
	}
	
	@Test
	public void buildMultipleStringTest() throws Exception {
		Map<String, Object> props = new HashMap<>();
		props.put("avro.schema.name", "src/test/resources/product.avsc src/test/resources/order_detail.avsc src/test/resources/order.avsc");
		builder.init(props);
		String result = builder.buildString(getElementsFromJson(json_complex));
		assertNotNull(result);
		assertTrue(result.length() > 0);
		assertTrue(result.startsWith("Obj"));
	}

	@Test
	@Ignore
	public void buildSplitStringTest() throws Exception {
		Map<String, Object> props = new HashMap<>();
		props.put("avro.schema.name", "src/test/resources/order_split.avsc");
		builder.init(props);
		String result = builder.buildString(getElementsFromJson(json_complex));
		assertNotNull(result);
		assertTrue(result.length() > 0);
		assertTrue(result.startsWith("Obj"));
	}
	
	private Map<DataKey, Elements> getElementsFromJson(String json) throws Exception {
		//DatumReader<GenericRecord> datumReader = new GenericDatumReader<GenericRecord>(schema);
		//GenericRecord record = datumReader.read(null, DecoderFactory.get().jsonDecoder(schema, json));
		JsonApiParser parser = new JsonApiParser(model);
		List<Data> elts = parser.parse(json);
		return convertData(elts);
	}
	
	private Map<DataKey, Elements> convertData(List<Data> elements) {
		Map<DataKey, Elements> result = new HashMap<>(elements.size());
		long docKey = 1L;
		for (Data data: elements) {
			DataKey key = new DataKey(docKey, data.getPathId());
			Elements elts = result.get(key); 
			if (elts == null) {
				elts = new Elements(data.getPathId(), null);
				result.put(key, elts);
			}
			elts.addElement(data.getElement());
		}
		return result;
	}

}
