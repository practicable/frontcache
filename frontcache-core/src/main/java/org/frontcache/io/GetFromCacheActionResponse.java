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
package org.frontcache.io;

import org.frontcache.core.WebResponse;

public class GetFromCacheActionResponse extends ActionResponse {

	private String key;
	private WebResponse value;

	public GetFromCacheActionResponse() { // for json mapper
		
	}
	
	public GetFromCacheActionResponse(String key) {
		setAction("get from cache");
		setResponseStatus(RESPONSE_STATUS_ERROR);
		this.key = key;
	}
	
	public GetFromCacheActionResponse(String key, WebResponse value) {
		setAction("get from cache");
		setResponseStatus(RESPONSE_STATUS_OK);
		this.key = key;
		this.value = value;
	}

	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public WebResponse getValue() {
		return value;
	}

	public void setValue(WebResponse value) {
		this.value = value;
	}


}
