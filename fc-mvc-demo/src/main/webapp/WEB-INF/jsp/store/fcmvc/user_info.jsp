<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://frontcache.org/core" prefix="fc" %>
<fc:component maxage="0" />



<c:choose>
 <c:when test="${null != user}">
 
	<c:out value="${user}" /> 	
    <br/><a href='<c:url value="/login?logout" />'>logout</a>
    
 </c:when>
 
 <c:otherwise>
    
    Anonymous
    <br/><a href='<c:url value="/login" />'>login</a>
 
 </c:otherwise>
</c:choose>
