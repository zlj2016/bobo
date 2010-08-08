package com.browseengine.solr;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.log4j.Logger;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.FacetParams;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.handler.component.SearchComponent;
import org.apache.solr.request.SolrQueryResponse;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QParserPlugin;
import org.apache.solr.search.QueryParsing;
import org.apache.solr.search.SolrIndexReader;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.SortSpec;
import org.apache.solr.util.SolrPluginUtils;

import com.browseengine.bobo.api.BoboBrowser;
import com.browseengine.bobo.api.BoboIndexReader;
import com.browseengine.bobo.api.BrowseFacet;
import com.browseengine.bobo.api.BrowseHit;
import com.browseengine.bobo.api.BrowseRequest;
import com.browseengine.bobo.api.BrowseResult;
import com.browseengine.bobo.api.FacetAccessible;
import com.browseengine.bobo.server.protocol.BoboRequestBuilder;

public class BoboFacetComponent extends SearchComponent {

	private static final String THREAD_POOL_SIZE_PARAM = "thread_pool_size";

	private static final String MAX_SHARD_COUNT_PARAM = "max_shard_count";
	
    private ExecutorService _threadPool = null;
	
	private static Logger logger=Logger.getLogger(BoboFacetComponent.class);
	
	public BoboFacetComponent() {
		// TODO Auto-generated constructor stub
	}
	
	public void init(NamedList params) {
		int threadPoolSize;
		try{
			threadPoolSize = Integer.parseInt((String)params.get(THREAD_POOL_SIZE_PARAM));
		}
		catch(Exception e){
			threadPoolSize = 100;
		}
		
		int shardCount;
		try{
			shardCount = Integer.parseInt((String)params.get(MAX_SHARD_COUNT_PARAM));
		}
		catch(Exception e){
			shardCount = 10;
		}
		
		_threadPool = Executors.newFixedThreadPool(threadPoolSize * shardCount);
	}

	@Override
	public String getDescription() {
		return "Handle Bobo Faceting";
	}

	@Override
	public String getSource() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getSourceId() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getVersion() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void prepare(ResponseBuilder rb) throws IOException {
	    if (rb.req.getParams().getBool(FacetParams.FACET,false)) {
	      rb.setNeedDocSet( true );
	      rb.doFacets = true;
	    }
	}

	@Override
	public void process(ResponseBuilder rb) throws IOException {
		rb.stage = ResponseBuilder.STAGE_START;
		SolrParams params = rb.req.getParams();
		// Set field flags
	    String fl = params.get(CommonParams.FL);
	    int fieldFlags = 0;
	    if (fl != null) {
	      fieldFlags |= SolrPluginUtils.setReturnFields(fl, rb.rsp);
	    }
	    rb.setFieldFlags( fieldFlags );

	    String defType = params.get(QueryParsing.DEFTYPE);
	    defType = defType==null ? QParserPlugin.DEFAULT_QTYPE : defType;

	    String qString = params.get( CommonParams.Q );
	    if (qString == null || qString.length()==0){
	    	qString="*:*";
	    }
	    if (rb.getQueryString() == null) {
	      rb.setQueryString( qString);
	    }

	    try {
	      QParser parser = QParser.getParser(rb.getQueryString(), defType, rb.req);
	      rb.setQuery( parser.getQuery() );
	      rb.setSortSpec( parser.getSort(true) );
	      rb.setQparser(parser);

	      /*
	      String[] fqs = params.getParams(CommonParams.FQ);
	      if (fqs!=null && fqs.length!=0) {
	        List<Query> filters = rb.getFilters();
	        if (filters==null) {
	          filters = new ArrayList<Query>();
	          rb.setFilters( filters );
	        }
	        for (String fq : fqs) {
	          if (fq != null && fq.trim().length()!=0) {
	            QParser fqp = QParser.getParser(fq, null, req);
	            filters.add(fqp.getQuery());
	          }
	        }
	      }*/
	      
	    } catch (ParseException e) {
	      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, e);
	    }
		
		Query query = rb.getQuery();
	    SortSpec sortspec = rb.getSortSpec();
	    Sort sort = null;
	    if (sortspec!=null){
	    	sort = sortspec.getSort();
	    }

	    SolrParams solrParams = rb.req.getParams();

		String shardsVal = solrParams.get(ShardParams.SHARDS, null);
		
	    BrowseRequest br=BoboRequestBuilder.buildRequest(solrParams,query,sort);
	    BrowseResult res = null;
	    if (shardsVal == null && !solrParams.getBool(ShardParams.IS_SHARD, false))
		{
			SolrIndexSearcher searcher=rb.req.getSearcher();
			
			SolrIndexReader solrReader = searcher.getReader();
			BoboIndexReader reader = (BoboIndexReader)solrReader.getWrappedReader();
			
			if (reader instanceof BoboIndexReader){
			    try {
	                BoboBrowser browser = new BoboBrowser(reader);
	                
					res=browser.browse(br);
					
				} catch (Exception e) {
					logger.error(e.getMessage(),e);
					throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,e.getMessage(),e);
				}
			   
			}
			else{
		        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,"invalid reader, please make sure BoboIndexReaderFactory is set.");
			}
		}
		else{
			// multi sharded request
			String[] shards = shardsVal.split(",");
			res = DispatchUtil.broadcast(_threadPool, solrParams, br, shards, 5);
		}
	    
	    SolrDocumentList docList = new SolrDocumentList();
	    
	    
	    docList.setNumFound(res.getNumHits());
	    docList.setStart(br.getOffset());
	    
		if (rb.doFacets) {
		  fillResponse(br,res,rb.rsp);
	    }

	    rb.stage = ResponseBuilder.STAGE_GET_FIELDS;
	    boolean returnScores = (rb.getFieldFlags() & SolrIndexSearcher.GET_SCORES) != 0;
	    
	    BrowseHit[] hits = res.getHits();
	    if (hits!=null){
	      for (BrowseHit hit : hits){
	    	SolrDocument doc = convert(hit);
	    	if (doc!=null){
	    		if (returnScores){
	    			doc.addField("score", hit.getScore());
	    		}
	    		docList.add(doc);
	    	}
	      }
	    }
	    
	    rb.rsp.add("response", docList);
	    rb.stage = ResponseBuilder.STAGE_DONE;
	}
	
	private static SolrDocument convert(BrowseHit hit){
		SolrDocument doc = new SolrDocument();
		Map<String,String[]> fieldVals = hit.getFieldValues();
		Set<Entry<String,String[]>> entries = fieldVals.entrySet();
		for (Entry<String,String[]> entry: entries){
		  doc.addField(entry.getKey(), entry.getValue());
		}
		return doc;
	}

	@Override
	public void finishStage(ResponseBuilder rb) {
	}

    private static void fillResponse(BrowseRequest req,BrowseResult res,SolrQueryResponse solrRsp){
		
		NamedList facetFieldList = new SimpleOrderedMap();
		Map<String,FacetAccessible> facetMap = res.getFacetMap();
		
		Set<Entry<String,FacetAccessible>> entries = facetMap.entrySet();
		for (Entry<String,FacetAccessible> entry : entries){
			
			NamedList facetList = new NamedList();
			facetFieldList.add(entry.getKey(), facetList);
			FacetAccessible facetAccessbile = entry.getValue();
			List<BrowseFacet> facets = facetAccessbile.getFacets();
			for (BrowseFacet facet : facets){
				facetList.add(facet.getValue(),facet.getFacetValueHitCount());
			}
		}
		
		NamedList facetResList = new SimpleOrderedMap();
		facetResList.add("facet_fields", facetFieldList);
		solrRsp.add( "facet_counts", facetResList );
	}
	
}
