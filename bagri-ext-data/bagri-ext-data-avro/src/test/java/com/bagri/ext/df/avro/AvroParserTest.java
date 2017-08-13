package com.bagri.ext.df.avro;

import static org.junit.Assert.*;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import com.bagri.core.server.api.impl.ModelManagementImpl;
import com.bagri.core.model.Data;


public class AvroParserTest {
	
	private static String json_simple = "{\"username\": \"miguno\", \"tweet\": \"Rock: Nerf paper, scissors is fine.\", \"timestamp\": 1366150681}";
	private static String json_complex = "{\"order_id\": 1000, \"customer_id\": 2000, \"total\": 3000.55, \"order_details\": ["
			+ "{\"quantity\": 10, \"total\": 25.56, \"product_detail\": {"
			+ "\"product_id\": 4000, \"product_name\": \"cucumber\", \"product_description\": null, \"product_status\": \"AVAILABLE\", "
			+ "\"product_category\": [\"VEGS\"], \"price\": 2.64, \"product_hash\": \"\u00FFu012\"}},"
			+ "{\"quantity\": 15, \"total\": 43.98, \"product_detail\": {"
			+ "\"product_id\": 5000, \"product_name\": \"apple\", \"product_description\": {\"string\": \"Golden\"}, \"product_status\": \"OUT_OF_STOCK\", "
			+ "\"product_category\": [\"FRUTS\", \"APPLES\"], \"price\": 1.345, \"product_hash\": \"\uFF00uEE0\"}}"
			+ "], \"product_map\": {\"cucumber\": 26.40, \"apple\": 18.85}}";
	
	private AvroParser parser;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		System.setProperty("logback.configurationFile", "test-logging.xml");
		//System.setProperty("user.country", "RU");
		//System.setProperty("user.language", "ru");
	}
	
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}

	@Before
	public void setUp() throws Exception {
		parser = new AvroParser(new ModelManagementImpl());
	}

	@After
	public void tearDown() throws Exception {
	}

	@Test
	@Ignore
	public void parseFileTest() throws Exception {
		
		Map<String, Object> props = new HashMap<>();
		props.put("avro.schema.name", "src/test/resources/twitter.avsc");
		parser.init(props);
		File file = new File("src/test/resources/twitter.avro");
		List<Data> data = parser.parse(file);
		assertNotNull(data);
		assertTrue(data.size() > 0);
	}

	@Test
	public void parseSimpleStringTest() throws Exception {
		
		Map<String, Object> props = new HashMap<>();
		props.put("avro.schema.name", "src/test/resources/twitter.avsc");
		parser.init(props);
		List<Data> data = parser.parse(json_simple);
		assertNotNull(data);
		assertTrue(data.size() > 0);
	}
	
	@Test
	public void parseComplexStringTest() throws Exception {

		Map<String, Object> props = new HashMap<>();
		props.put("avro.schema.name", "src/test/resources/order_complex.avsc");
		parser.init(props);
		List<Data> data = parser.parse(json_complex);
		assertNotNull(data);
		assertTrue(data.size() > 0);
	}
	
	@Test
	public void parseMultipleStringTest() throws Exception {
		Map<String, Object> props = new HashMap<>();
		props.put("avro.schema.name", "src/test/resources/product.avsc src/test/resources/order_detail.avsc src/test/resources/order.avsc");
		parser.init(props);
		List<Data> data = parser.parse(json_complex);
		assertNotNull(data);
		assertTrue(data.size() > 0);
	}

	
}
