package org.frontcache.hystrix;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.message.BasicHttpRequest;
import org.frontcache.core.FCUtils;
import org.frontcache.core.RequestContext;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixCommandProperties.ExecutionIsolationStrategy;

public class BypassFrontcache extends HystrixCommand<Object> {

	private final HttpClient client;

    public BypassFrontcache(HttpClient client) {
        
        super(Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("Frontcache"))
                .andCommandKey(HystrixCommandKey.Factory.asKey("BypassFrontcache"))
                .andCommandPropertiesDefaults(
                        HystrixCommandProperties.Setter()
                                .withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE)));
        
        this.client = client;
    }

    @Override
    protected Object run() throws Exception {

    	forwardToOrigin();
    	
    	return null;
    }
    
    
	private void forwardToOrigin() throws IOException, ServletException
	{
		RequestContext context = RequestContext.getCurrentContext();
		HttpServletRequest request = context.getRequest();
		
		if (context.isFilterMode())
		{
			HttpServletResponse response = context.getResponse();
			FilterChain chain = context.getFilterChain();
			chain.doFilter(request, response);
		} else {
			
			// stand alone mode
			
			MultiValuedMap<String, String> headers = FCUtils.buildRequestHeaders(request);
			MultiValuedMap<String, String> params = FCUtils.builRequestQueryParams(request);
			String verb = FCUtils.getVerb(request);
			InputStream requestEntity = getRequestBody(request);
			String uri = context.getRequestURI();

			try {
				HttpResponse response = forward(client, verb, uri, request, headers, params, requestEntity);
				
				// response 2 context
				setResponse(response);
				
			}
			catch (Exception ex) {
				ex.printStackTrace();
				context.set("error.status_code", HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				context.set("error.exception", ex);
			}
		}
		
		return;
	}
    
	
	private void setResponse(HttpResponse response) throws IOException {
		
		RequestContext context = RequestContext.getCurrentContext();
		context.setHttpClientResponse((CloseableHttpResponse) response);
		
		setResponse(response.getStatusLine().getStatusCode(),
				response.getEntity() == null ? null : response.getEntity().getContent(),
				FCUtils.revertHeaders(response.getAllHeaders()));
	}

	
	private void setResponse(int status, InputStream entity, MultiValuedMap<String, String> headers) throws IOException {
		RequestContext context = RequestContext.getCurrentContext();
		
		context.setResponseStatusCode(status);
		
		if (entity != null) {
			context.setResponseDataStream(entity);
		}
		
		for (String key : headers.keySet()) {
			for (String value : headers.get(key)) {
				context.addOriginResponseHeader(key, value);
			}
		}

	}	
	

	private InputStream getRequestBody(HttpServletRequest request) {
		InputStream requestEntity = null;
		try {
			requestEntity = request.getInputStream();
		}
		catch (IOException ex) {
			// no requestBody is ok.
		}
		return requestEntity;
	}	
	
	/**
	 * forward all kind of requests (GET, POST, PUT, ...)
	 * 
	 * @param httpclient
	 * @param verb
	 * @param uri
	 * @param request
	 * @param headers
	 * @param params
	 * @param requestEntity
	 * @return
	 * @throws Exception
	 */
	private HttpResponse forward(HttpClient httpclient, String verb, String uri, HttpServletRequest request,
			MultiValuedMap<String, String> headers, MultiValuedMap<String, String> params, InputStream requestEntity)
					throws Exception {
		RequestContext context = RequestContext.getCurrentContext();

		URL host = context.getOriginURL();
		HttpHost httpHost = FCUtils.getHttpHost(host);
		uri = (host.getPath() + uri).replaceAll("/{2,}", "/");
		
		HttpRequest httpRequest;
		switch (verb.toUpperCase()) {
		case "POST":
			HttpPost httpPost = new HttpPost(uri + context.getRequestQueryString());
			httpRequest = httpPost;
			httpPost.setEntity(new InputStreamEntity(requestEntity, request.getContentLength()));
			break;
		case "PUT":
			HttpPut httpPut = new HttpPut(uri + context.getRequestQueryString());
			httpRequest = httpPut;
			httpPut.setEntity(new InputStreamEntity(requestEntity, request.getContentLength()));
			break;
		case "PATCH":
			HttpPatch httpPatch = new HttpPatch(uri + context.getRequestQueryString());
			httpRequest = httpPatch;
			httpPatch.setEntity(new InputStreamEntity(requestEntity, request.getContentLength()));
			break;
		default:
			httpRequest = new BasicHttpRequest(verb, uri + context.getRequestQueryString());
		}
		
		
		try {
			httpRequest.setHeaders(FCUtils.convertHeaders(headers));
			Header acceptEncoding = httpRequest.getFirstHeader("accept-encoding");
			if (acceptEncoding != null && acceptEncoding.getValue().contains("gzip"))
			{
				httpRequest.setHeader("accept-encoding", "gzip");
			}
			HttpResponse originResponse = httpclient.execute(httpHost, httpRequest);
			return originResponse;
		} finally {
			// When HttpClient instance is no longer needed,
			// shut down the connection manager to ensure
			// immediate deallocation of all system resources
			// httpclient.getConnectionManager().shutdown();
		}
	}	
    
}