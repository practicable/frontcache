/**
 *        Copyright 2017 Eternita LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.cache.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermContext;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermStatistics;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.frontcache.core.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Provides access to lucene index.
 *
 */
public class LuceneIndexManager {

	private static final Logger logger = LoggerFactory.getLogger(LuceneIndexManager.class);

	private static String INDEX_PATH;
	
	public static final String JSON_FIELD = "json";
	public static final String BIN_FIELD = "bin";

	// searchable fields
	public static final String TAGS_FIELD = "tags"; // for invalidation
	public static final String URL_FIELD = "url"; 
	public static final String DOMAIN_FIELD = "domain"; // for shared / multidomain mode 
	public static final String EXPIRE_DATE_FIELD = "expire_date"; 
	
	private IndexWriter indexWriter = null;
	
	public final static FieldType JSON_TYPE;
	static {
	    JSON_TYPE = new FieldType();
	    JSON_TYPE.setStored(true);
	    JSON_TYPE.setIndexOptions(IndexOptions.NONE);
	    JSON_TYPE.setTokenized(false);
	    JSON_TYPE.freeze();
	}
	
	/**
	 *  
	 */
	final Gson gson = new GsonBuilder().addSerializationExclusionStrategy(new ExclusionStrategy() {

		@Override
		public boolean shouldSkipField(FieldAttributes att) {
			return false;
		}

		@Override
		public boolean shouldSkipClass(Class<?> arg0) {
			return byte[].class.equals(arg0);
		}
	}).create();

	
	/**
	 * Constructor
	 * @param indexPath
	 */
	public LuceneIndexManager(String indexPath) {
		INDEX_PATH = indexPath;
		Path path = Paths.get(INDEX_PATH);
		if (!Files.exists(path)) {
			try {
				if (path.getParent().toFile().isDirectory() && !path.getParent().toFile().exists())
					Files.createDirectories(path.getParent());
				
				// create dummy index
				{
					String url = UUID.randomUUID().toString();
					String domain = UUID.randomUUID().toString();
					WebResponse dummy =  new WebResponse(url);
					dummy.setDomain(domain);
					indexDoc(dummy);
					delete(domain, url);
				}
			} catch (IOException e) {
				logger.error("Error during creating cache-file", e);
			}
		} else {
			try {
				indexWriter = getIndexWriter();
			} catch (Exception e) {
				// if other instance holds lock on index - lets try to get lock during runtime
				logger.error("Error during creating indexWriter " + e.getMessage());
			}
		}
	}

	/**
	 * Returns instance of IndexManager
	 * @param create
	 * @return
	 * @throws IOException
	 */
	private IndexWriter getIndexWriter() throws IOException {
		if (indexWriter == null || !indexWriter.isOpen()) {
			synchronized (this) {
				if (indexWriter == null || !indexWriter.isOpen()) {
					indexWriter = null;
					logger.info("Trying to get indexWriter...");
					Directory dir = FSDirectory.open(Paths.get(INDEX_PATH));
					Analyzer analyzer = new StandardAnalyzer();
					IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
	
					iwc.setOpenMode(OpenMode.CREATE_OR_APPEND);
					iwc.setRAMBufferSizeMB(250.0);
					indexWriter = new IndexWriter(dir, iwc);
					logger.info("IndexWriter initialized");
				}
			}
		}

		return indexWriter;
	}

	/**
	 * Writes webResponse to index
	 * @param response
	 * @throws IOException
	 */
	void indexDoc(WebResponse response) throws IOException {

		IndexWriter iWriter = getIndexWriter();

		Document doc = new Document();

		String url = response.getUrl();
		
		if (null == url)
		{
			logger.error("URL can't be null during index time for " + response);
			return;
		}

		doc.add(new StringField(URL_FIELD, url, Field.Store.YES));
		
		doc.add(new StringField(DOMAIN_FIELD, response.getDomain(), Field.Store.YES));
		
		if (null != response.getContent())
			doc.add(new StoredField(BIN_FIELD, response.getContent()));
		
//		doc.add(new NumericDocValuesField(EXPIRE_DATE_FIELD, response.getExpireTimeMillis())); // TODO: store map ?
		
		doc.add(new StoredField(JSON_FIELD, gson.toJson(response), JSON_TYPE));
		
		for (String tag : response.getTags())
			doc.add(new StringField(TAGS_FIELD, tag, Field.Store.NO)); // tag is StringField to exact match
			
		try {
			iWriter.updateDocument(new Term(URL_FIELD, url), doc);
		} catch (IOException e) {
			logger.error("Error while in Lucene index operation: {}", e.getMessage(), e);

		} finally {
			try {
				iWriter.commit();
			} catch (IOException ioEx) {
				logger.error("Error while commiting changes to Lucene index: {}", ioEx.getMessage(), ioEx);
			}
		}

	}
	
	public void close() {
		if (indexWriter != null && indexWriter.isOpen()) {
			try {
				indexWriter.close();
			} catch (Exception e) {
				logger.error("Error:", e);
			}
		}
	}

	/**
	 * Returns document based on url
	 */
	private Document getDocByURL(String url) throws IOException, ParseException {

		IndexWriter iWriter = null;
		try {
			iWriter = getIndexWriter();
			if (iWriter == null){
				return null;
			}
		} catch (Exception e1) {
			logger.debug("Error during getting indexWriter. " + e1.getMessage());
			return null;
		}
		
		Document doc = null;
		IndexReader reader = null;
		try {
			reader = DirectoryReader.open(iWriter);

			IndexSearcher searcher = new IndexSearcher(reader);

			Term term = new Term(URL_FIELD, url);
			Query query = new TermQuery(term);
			
			TopDocs results = searcher.search(query, 1);

			if (results.scoreDocs != null) {
				if (results.scoreDocs.length == 1) {
					doc = searcher.doc(results.scoreDocs[0].doc);
				}
			}
		} finally {
			if (reader != null) {
				reader.close();
			}
		}

		return doc;
	}
	

	public long getDocumentsCount(String domain) {

		long count = -1;
		
		IndexWriter iWriter = null;
		try {
			iWriter = getIndexWriter();
			if (iWriter == null){
				return count;
			}
		} catch (Exception e1) {
			logger.debug("Error during getting indexWriter. " + e1.getMessage());
			return count;
		}
		
		IndexReader reader = null;
		try {
			reader = DirectoryReader.open(iWriter);
			Term domainTerm = new Term(DOMAIN_FIELD, domain);
			IndexSearcher searcher = new IndexSearcher(reader);
			TermStatistics termStat = searcher.termStatistics(domainTerm, TermContext.build(searcher.getIndexReader().getContext(), domainTerm));
			count = termStat.docFreq();
		} catch (Exception e1) {
			logger.debug("Error during reader.totalTermFreq(domainTerm). " + e1.getMessage());
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}

		return count;
	}
	
	/**
	 * Removes documents by url or tags
	 * @param urlOrTag
	 */
	public void delete(String domain, String urlOrTag) {
		
		IndexWriter iWriter = null;
		try {
			iWriter = getIndexWriter();
			if (iWriter == null){
				return ;
			}
		} catch (Exception e1) {
			logger.debug("Error during getting indexWriter. " + e1.getMessage());
			return;
		}

		
		try {
//			Query domainQuery = new TermQuery(new Term(DOMAIN_FIELD, domain));
			Query urlQuery = new TermQuery(new Term(URL_FIELD, urlOrTag));
			Query tagsQuery = new TermQuery(new Term(TAGS_FIELD, urlOrTag));
			
			BooleanQuery.Builder booleanQuery = new BooleanQuery.Builder();
			
//			booleanQuery.add(domainQuery, Occur.MUST);
			booleanQuery.add(urlQuery, Occur.SHOULD);
			booleanQuery.add(tagsQuery, Occur.SHOULD);
			
			long count = iWriter.deleteDocuments(booleanQuery.build());
			logger.debug("Removed  {} documents for {}.", count, urlOrTag);
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
		} finally {
			try {
				iWriter.commit();
			} catch (IOException e) {
				logger.error(e.getMessage(), e);
			}
		}
	}

	public void delete(String urlOrTag) {
		
		IndexWriter iWriter = null;
		try {
			iWriter = getIndexWriter();
			if (iWriter == null){
				return ;
			}
		} catch (Exception e1) {
			logger.debug("Error during getting indexWriter. " + e1.getMessage());
			return;
		}
		
		try {
			Query urlQuery = new TermQuery(new Term(URL_FIELD, urlOrTag));
			Query tagsQuery = new TermQuery(new Term(TAGS_FIELD, urlOrTag));
			
			BooleanQuery.Builder booleanQuery = new BooleanQuery.Builder();
			
			booleanQuery.add(urlQuery, Occur.SHOULD);
			booleanQuery.add(tagsQuery, Occur.SHOULD);
			
			long count = iWriter.deleteDocuments(booleanQuery.build());
			logger.debug("Removed  {} documents for {}.", count, urlOrTag);
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
		} finally {
			try {
				iWriter.commit();
			} catch (IOException e) {
				logger.error(e.getMessage(), e);
			}
		}
	}
	
	/**
	 * Removes documents by url or tags
	 * @param urlOrTag
	 */
	public void deleteAll(String domain) {
		
		if (null == domain)
		{
			logger.error("Cant delete all with null domain");
			return;
		}
			
		IndexWriter iWriter = null;
		try {
			iWriter = getIndexWriter();
			if (iWriter == null){
				return ;
			}
		} catch (Exception e1) {
			logger.debug("Error during getting indexWriter. " + e1.getMessage());
			return;
		}

		
		try {
			logger.debug("Removing all documents for {}.", domain);
			
			Query domainQuery = new TermQuery(new Term(DOMAIN_FIELD, domain));
			
			long count = iWriter.deleteDocuments(domainQuery);
			logger.debug("Removed  {} documents for {}.", count, domain);
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
		} finally {
			try {
				iWriter.commit();
			} catch (IOException e) {
				logger.error(e.getMessage(), e);
			}
		}
	}
	
	public void deleteExpired() {
		// TODO: implement me : query EXPIRE_DATE_FIELD and delete
	}
	
	/**
	 * 
	 * @return
	 */
	public List<String> getKeys() 
	{
		
		IndexWriter iWriter = null;
		try {
			iWriter = getIndexWriter();
			if (iWriter == null){
				return Collections.emptyList();
			}
		} catch (Exception e1) {
			logger.debug("Error during getting indexWriter. " + e1.getMessage());
			return Collections.emptyList();
		}
		
		List<String> keys = new ArrayList<String>();

		IndexReader reader = null;
		try {
			reader = DirectoryReader.open(iWriter);
			
			for (int i=0; i<reader.numDocs(); i++) {
			    Document doc = reader.document(i);
			    if (null != doc)
			    {
				    if (null != doc.get(URL_FIELD))
				    	keys.add(doc.get(URL_FIELD));
				    else
				    	logger.error("URL is null for doc (probably corrupted after/during index time) " + doc);
			    }
			    

			}
		} catch (Exception e) {
			logger.error("Error during loading urls/keys from index", e);
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		
		return keys;
	}

	/**
	 * Gets response from index
	 * @param url request url
	 * @return WebResponse from index
	 */
	public WebResponse getResponse(String url) {
		WebResponse response = null;
		try {
			Document doc = getDocByURL(url);
			if (doc != null) {
				response = gson.fromJson(doc.get(JSON_FIELD), WebResponse.class);
				BytesRef bin1ref = doc.getBinaryValue(BIN_FIELD);
				if (null != bin1ref)
					response.setContent(bin1ref.bytes);
			}

			return response;
		} catch (Exception e) {
			logger.error("Error during loading data from index", e);
		}
		return null;
	}
	
	/**
	 * Returns index size
	 * @return
	 */
	public int getIndexSize(){
		int n = -1;
		IndexReader reader = null;
		try {
			
			Directory dir = FSDirectory.open(Paths.get(INDEX_PATH));
			reader = DirectoryReader.open(dir);
			n = reader.numDocs();

		} catch (IOException e) {
			
			logger.error(e.getMessage(), e);
		} finally {
			
			if (null != reader)
			{
				try {
					reader.close();
				} catch (IOException e) {
					logger.error(e.getMessage(), e);
				}
			}
		}
		
		return n;
	}
	

}
