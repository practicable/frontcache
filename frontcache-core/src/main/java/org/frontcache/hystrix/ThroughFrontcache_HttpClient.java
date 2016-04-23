package org.frontcache.hystrix;

import java.io.IOException;
import java.net.URL;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.WebResponse;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixCommandProperties.ExecutionIsolationStrategy;

public class ThroughFrontcache_HttpClient extends HystrixCommand<WebResponse> {

	private final String urlStr;
	private final MultiValuedMap<String, String> requestHeaders;
	private final HttpClient client;

    public ThroughFrontcache_HttpClient(String urlStr, MultiValuedMap<String, String> requestHeaders, HttpClient client) {
        
        super(Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("Frontcache"))
                .andCommandKey(HystrixCommandKey.Factory.asKey("ThroughFrontcache_HttpClient"))
                .andCommandPropertiesDefaults(
                        HystrixCommandProperties.Setter()
                                .withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE)));
        
        this.urlStr = urlStr;
        this.requestHeaders = requestHeaders;
        this.client = client;
    }

    @Override
    protected WebResponse run() throws FrontCacheException {
		HttpResponse response = null;

		try {
			HttpHost httpHost = FCUtils.getHttpHost(new URL(urlStr));
			HttpRequest httpRequest = new HttpGet(FCUtils.buildRequestURI(urlStr));//(verb, uri + context.getRequestQueryString());

			// translate headers
			Header[] httpHeaders = FCUtils.convertHeaders(requestHeaders);
			for (Header header : httpHeaders)
				httpRequest.addHeader(header);
			
			response = client.execute(httpHost, httpRequest);
			WebResponse webResp = FCUtils.httpResponse2WebComponent(urlStr, response);
			return webResp;

		} catch (IOException ioe) {
			throw new FrontCacheException("Can't read from " + urlStr, ioe);
		} finally {
			if (null != response)
				try {
					((CloseableHttpResponse) response).close();
				} catch (IOException e) {
					e.printStackTrace();
				} 
		}
		
    }
    
    @Override
    protected WebResponse getFallback() {
        return fallbackForWebComponent(this.urlStr);
    }
    
	
	private WebResponse fallbackForWebComponent(String urlStr)
	{
		byte[] outContentBody = ("Fallabck for " + urlStr).getBytes();

		WebResponse webResponse = new WebResponse(urlStr, outContentBody, CacheProcessor.NO_CACHE);
		String contentType = "text/html";
		webResponse.setContentType(contentType);
		
		int httpResponseCode = 200;
		webResponse.setStatusCode(httpResponseCode);

		return webResponse;
	}
    
}