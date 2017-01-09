package org.jenkinsci.plugins.cas.validation;


import java.io.StringReader;
import java.util.*;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.jasig.cas.client.authentication.AttributePrincipal;
import org.jasig.cas.client.authentication.AttributePrincipalImpl;
import org.jasig.cas.client.proxy.Cas20ProxyRetriever;
import org.jasig.cas.client.proxy.ProxyGrantingTicketStorage;
import org.jasig.cas.client.proxy.ProxyRetriever;
import org.jasig.cas.client.util.CommonUtils;
import org.jasig.cas.client.util.XmlUtils;

import org.jasig.cas.client.validation.AbstractCasProtocolUrlBasedTicketValidator;
import org.jasig.cas.client.validation.Assertion;
import org.jasig.cas.client.validation.AssertionImpl;
import org.jasig.cas.client.validation.TicketValidationException;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Implementation of the TicketValidator that will validate Service Tickets in compliance with the CAS 2.
 *
 * @author Scott Battaglia
 * @since 3.1
 */
public class Cas20SinaParsingTicketValidator extends AbstractCasProtocolUrlBasedTicketValidator {

    /** The CAS 2.0 protocol proxy callback url. */
    private String proxyCallbackUrl;

    /** The storage location of the proxy granting tickets. */
    private ProxyGrantingTicketStorage proxyGrantingTicketStorage;

    /** Implementation of the proxy retriever. */
    private ProxyRetriever proxyRetriever;

    /**
     * Constructs an instance of the CAS 2.0 Service Ticket Validator with the supplied
     * CAS server url prefix.
     *
     * @param casServerUrlPrefix the CAS Server URL prefix.
     * @param urlFactory URL connection factory to use when communicating with the server
     */
    public Cas20SinaParsingTicketValidator(final String casServerUrlPrefix) {
        super(casServerUrlPrefix);
        this.setEncoding("GB2312");

        this.proxyRetriever = new Cas20ProxyRetriever(casServerUrlPrefix, getEncoding(), getURLConnectionFactory());
    }

    /**
     * Adds the pgtUrl to the list of parameters to pass to the CAS server.
     *
     * @param urlParameters the Map containing the existing parameters to send to the server.
     */
    protected final void populateUrlAttributeMap(final Map<String, String> urlParameters) {
        urlParameters.put("pgtUrl", this.proxyCallbackUrl);
    }

    protected String getUrlSuffix() {
        return "validate";
    }

    protected boolean isValueNotBlank(String value) {
        return CommonUtils.isNotBlank(value) && !("null".equals(value)) && !("0000".equals(value)) && !("0".equals(value));
    }

    protected final Assertion parseResponseFromServer(final String response) throws TicketValidationException {
        final String error = XmlUtils.getTextForElement(response, "authenticationFailure");

        if (CommonUtils.isNotBlank(error)) {
            throw new TicketValidationException(error);
        }

        /*
        <?xml version="1.0" encoding="GB2312" ?>
<user>
    <info>
        <username>12222</username>
        <email>cxxxx</email>
        <uid>00525D57-9AB9-49CE-8CB4-C652A8F3C742</uid>
        <fullemail>cxxx@staff.wxxxxxx</fullemail>
        <name>张三xxx</name>
        <erpname>张三xxx</erpname>
        <organization>部门</organization>
        <organizationt3>汇报线</organizationt3>
        <telephone>1234</telephone>
    </info>
  <acl></acl>
</user>
<!-- 技术联系人：xxxx(eeeee@,1234) -->
        */

        // String principal = XmlUtils.getTextForElement(response, "email");
        final String principal = XmlUtils.getTextForElement(response, "email");
        final String proxyGrantingTicketIou = XmlUtils.getTextForElement(response, "proxyGrantingTicket");

        final String proxyGrantingTicket;
        if (CommonUtils.isBlank(proxyGrantingTicketIou) || this.proxyGrantingTicketStorage == null) {
            proxyGrantingTicket = null;
        } else {
            proxyGrantingTicket = this.proxyGrantingTicketStorage.retrieve(proxyGrantingTicketIou);
        }

        if (CommonUtils.isEmpty(principal)) {
            throw new TicketValidationException("No principal was found in the response from the CAS server.");
        }

        final Assertion assertion;
        final Map<String, Object> attributes = extractCustomAttributes(response);

        // if (principal.equals("chengwei2")) {
        //     principal = "cibot";

        //     attributes.put("email", "cibot");
        //     attributes.put("fullemail", "cibot@staff.sina.com.cn");
        //     attributes.put("name", "cibot");
        //     attributes.put("erpname", "cibot");
        // }

        String sn_name = (String) attributes.get("name");
            
        String sn_id = (String) attributes.get("username");
        if (isValueNotBlank(sn_name) && isValueNotBlank(sn_id)) {
            sn_name = sn_name.replace(sn_id, "");
            attributes.put("name", sn_name);
        }
        
        String sn_tel = (String) attributes.get("telephone");
        
        String sn_name_tel = principal;
        
        if (isValueNotBlank(sn_name) && isValueNotBlank(sn_tel)) {
            sn_name_tel = sn_name + "(" + principal + "@," + sn_tel + ")";
        } else if (isValueNotBlank(sn_name)) {
            sn_name_tel = sn_name + "(" + principal + "@)";
        } else if (isValueNotBlank(sn_tel)) {
            sn_name_tel = principal + "(" + sn_tel + ")";
        }

        attributes.put("name_tel", sn_name_tel);

        if (CommonUtils.isNotBlank(proxyGrantingTicket)) {
            final AttributePrincipal attributePrincipal = new AttributePrincipalImpl(principal, attributes,
                    proxyGrantingTicket, this.proxyRetriever);
            assertion = new AssertionImpl(attributePrincipal);
        } else {
            assertion = new AssertionImpl(new AttributePrincipalImpl(principal, attributes));
        }

        customParseResponse(response, assertion);

        return assertion;
    }

    /**
     * Default attribute parsing of attributes that look like the following:
     * &lt;cas:attributes&gt;
     *  &lt;cas:attribute1&gt;value&lt;/cas:attribute1&gt;
     *  &lt;cas:attribute2&gt;value&lt;/cas:attribute2&gt;
     * &lt;/cas:attributes&gt;
     * <p>
     *
     * Attributes look like following also parsed correctly:
     * &lt;cas:attributes&gt;&lt;cas:attribute1&gt;value&lt;/cas:attribute1&gt;&lt;cas:attribute2&gt;value&lt;/cas:attribute2&gt;&lt;/cas:attributes&gt;
     * <p>
     *
     * This code is here merely for sample/demonstration purposes for those wishing to modify the CAS2 protocol.  You'll
     * probably want a more robust implementation or to use SAML 1.1
     *
     * @param xml the XML to parse.
     * @return the map of attributes.
     */
    protected Map<String, Object> extractCustomAttributes(final String xml) {
        final SAXParserFactory spf = SAXParserFactory.newInstance();
        spf.setNamespaceAware(true);
        spf.setValidating(false);
        try {
            final SAXParser saxParser = spf.newSAXParser();
            final XMLReader xmlReader = saxParser.getXMLReader();
            final CustomAttributeHandler handler = new CustomAttributeHandler();
            xmlReader.setContentHandler(handler);
            xmlReader.parse(new InputSource(new StringReader(xml)));
            return handler.getAttributes();
        } catch (final Exception e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    /**
     * Template method if additional custom parsing (such as Proxying) needs to be done.
     *
     * @param response the original response from the CAS server.
     * @param assertion the partially constructed assertion.
     * @throws TicketValidationException if there is a problem constructing the Assertion.
     */
    protected void customParseResponse(final String response, final Assertion assertion)
            throws TicketValidationException {
        // nothing to do
    }

    public final void setProxyCallbackUrl(final String proxyCallbackUrl) {
        this.proxyCallbackUrl = proxyCallbackUrl;
    }

    public final void setProxyGrantingTicketStorage(final ProxyGrantingTicketStorage proxyGrantingTicketStorage) {
        this.proxyGrantingTicketStorage = proxyGrantingTicketStorage;
    }

    public final void setProxyRetriever(final ProxyRetriever proxyRetriever) {
        this.proxyRetriever = proxyRetriever;
    }

    protected final String getProxyCallbackUrl() {
        return this.proxyCallbackUrl;
    }

    protected final ProxyGrantingTicketStorage getProxyGrantingTicketStorage() {
        return this.proxyGrantingTicketStorage;
    }

    protected final ProxyRetriever getProxyRetriever() {
        return this.proxyRetriever;
    }

    private class CustomAttributeHandler extends DefaultHandler {

        private Map<String, Object> attributes;

        private boolean foundAttributes;

        private String currentAttribute;

        private StringBuilder value;

        @Override
        public void startDocument() throws SAXException {
            this.attributes = new HashMap<String, Object>();
        }

        @Override
        public void startElement(final String namespaceURI, final String localName, final String qName,
                final Attributes attributes) throws SAXException {
            if ("info".equals(localName)) {
                this.foundAttributes = true;
            } else if (this.foundAttributes) {
                this.value = new StringBuilder();
                this.currentAttribute = localName;
            }
        }

        @Override
        public void characters(final char[] chars, final int start, final int length) throws SAXException {
            if (this.currentAttribute != null) {
                value.append(chars, start, length);
            }
        }

        @Override
        public void endElement(final String namespaceURI, final String localName, final String qName)
                throws SAXException {
            if ("info".equals(localName)) {
                this.foundAttributes = false;
                this.currentAttribute = null;
            } else if (this.foundAttributes) {
                final Object o = this.attributes.get(this.currentAttribute);

                if (o == null) {
                    this.attributes.put(this.currentAttribute, this.value.toString());
                } else {
                    final List<Object> items;
                    if (o instanceof List) {
                        items = (List<Object>) o;
                    } else {
                        items = new LinkedList<Object>();
                        items.add(o);
                        this.attributes.put(this.currentAttribute, items);
                    }
                    items.add(this.value.toString());
                }
            }
        }

        public Map<String, Object> getAttributes() {
            return this.attributes;
        }
    }
}