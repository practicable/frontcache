package org.frontcache.tests.filter;

import org.frontcache.tests.base.HTTPResponseCodeTests;

public class FilterHTTPResponseCodeTests extends HTTPResponseCodeTests {

	@Override
	public String getFrontCacheBaseURLDomainFC1() {
		return getFilterBaseURLDomainFC1();
	}

}
