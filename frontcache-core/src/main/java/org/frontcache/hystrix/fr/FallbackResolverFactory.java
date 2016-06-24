package org.frontcache.hystrix.fr;


import org.apache.http.client.HttpClient;
import org.frontcache.FCConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class FallbackResolverFactory {

	private static Logger logger = LoggerFactory.getLogger(FallbackResolverFactory.class);
	private FallbackResolverFactory() {}
	
	private static FallbackResolver instance;

	public static FallbackResolver getInstance(HttpClient client){
		if (null == instance) {
			instance = getFallbackResolver();
			instance.init(client);
		}
		return instance;
	}
	
	
	private static FallbackResolver getFallbackResolver()
	{
		String implStr = FCConfig.getProperty("front-cache.fallback-resolver.impl");
		try
		{

			Class clazz = Class.forName(implStr);
			Object obj = clazz.newInstance();
			if (null != obj && obj instanceof FallbackResolver)
			{
				logger.info("FallbackResolver implementation loaded: " + implStr);
				FallbackResolver fallbackResolver = (FallbackResolver) obj;

				return fallbackResolver;
			}
		} catch (Exception ex) {
			logger.error("Cant instantiate " + implStr + ". Default implementation is loaded: " + DefaultFallbackResolver.class.getCanonicalName());
			
			// 
			return new DefaultFallbackResolver();
		}
		
		
		return null;
	}

}