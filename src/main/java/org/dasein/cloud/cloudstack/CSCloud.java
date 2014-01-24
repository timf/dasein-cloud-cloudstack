/**
 * Copyright (C) 2009-2013 enstratius, Inc.
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */

package org.dasein.cloud.cloudstack;

import java.io.UnsupportedEncodingException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Properties;

import org.apache.log4j.Logger;
import org.dasein.cloud.AbstractCloud;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.cloudstack.compute.CSComputeServices;
import org.dasein.cloud.cloudstack.identity.CSIdentityServices;
import org.dasein.cloud.cloudstack.network.CSNetworkServices;
import org.dasein.cloud.storage.BlobStoreSupport;
import org.dasein.cloud.storage.StorageServices;
import org.dasein.cloud.util.APITrace;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletResponse;

public class CSCloud extends AbstractCloud {
    static private final Logger logger = getLogger(CSCloud.class, "std");
    static public final String LIST_ACCOUNTS = "listAccounts";

    static private @Nonnull String getLastItem(@Nonnull String name) {
        int idx = name.lastIndexOf('.');
        
        if( idx < 0 ) {
            return name;
        }
        else if( idx == (name.length()-1) ) {
            return "";
        }
        return name.substring(idx+1);
    }
    
    static public @Nonnull Logger getLogger(@Nonnull Class<?> cls, @Nonnull String type) {
        String pkg = getLastItem(cls.getPackage().getName());
        
        if( pkg.equals("cloudstack") ) {
            pkg = "";
        }
        else {
            pkg = pkg + ".";
        }
        return Logger.getLogger("dasein.cloud.cloudstack." + type + "." + pkg + getLastItem(cls.getName()));
    }
    
    public CSCloud() { }
    
    @Override
    public @Nonnull String getCloudName() {
        ProviderContext ctx = getContext();

        if( ctx == null ) {
            return "Citrix";
        }
        String name = ctx.getCloudName();
        
        if( name == null ) {
            return "Citrix";
        }
        return name;
    }
    
    @Override
    public @Nonnull CSComputeServices getComputeServices() {
        return new CSComputeServices(this);
    }
    
    @Override
    public @Nonnull CSTopology getDataCenterServices() {
        return new CSTopology(this);
    }
    
    @Override
    public @Nullable CSIdentityServices getIdentityServices() {
        if( getVersion().greaterThan(CSVersion.CS21) ) {
            return new CSIdentityServices(this);
        }
        return null;
    }

    @Override
    public @Nonnull CSNetworkServices getNetworkServices() {
        return new CSNetworkServices(this);
    }
    
    @Override
    public @Nonnull String getProviderName() {
        ProviderContext ctx = getContext();

        if( ctx == null ) {
            return "Citrix";
        }
        String name = ctx.getProviderName();
        
        if( name == null ) {
            return "Citrix";
        }
        return name;
    }

    private transient CSServiceProvider serviceProvider;

    public CSServiceProvider getServiceProvider() {
        if( serviceProvider == null ) {
            String pn = getProviderName();

            if( "kt".equalsIgnoreCase(pn) ) {
                serviceProvider = CSServiceProvider.KT;
            }
            else if( "datapipe".equalsIgnoreCase(pn) ) {
                serviceProvider = CSServiceProvider.DATAPIPE;
            }
            else if( "tata".equalsIgnoreCase(pn) ) {
                serviceProvider = CSServiceProvider.TATA;
            } else if( "democloud".equalsIgnoreCase(pn) ) {
                serviceProvider = CSServiceProvider.DEMOCLOUD;
            }
            else {
                serviceProvider = CSServiceProvider.INTERNAL;
            }
        }
        return serviceProvider;
    }

    private transient CSVersion version;

    public @Nonnull
    CSVersion getVersion() {
        if( version == null ) {
            ProviderContext ctx = getContext();
            Properties properties = (ctx == null ? null : ctx.getCustomProperties());
            String versionString;

            if (properties == null || properties.getProperty("apiVersion") == null || properties.getProperty("apiVersion").equals("")) {
               //run list zone query to check whether this might be v4
                try {
                    CSMethod method = new CSMethod(this);
                    String url = method.buildUrl("listZones", new Param("available", "true"));
                    Document doc = method.get(url, "listZones");
                    NodeList meta = doc.getElementsByTagName("listzonesresponse");
                    for (int item = 0; item<meta.getLength(); item++) {
                        Node node = meta.item(item);
                        String v = node.getAttributes().getNamedItem("cloud-stack-version").getNodeValue();
                        if (v.startsWith("4")) {
                            if (properties == null) {
                                properties = new Properties();
                            }
                            properties.setProperty("apiVersion", "CS4");
                            logger.info("Version property not found so setting based on result of query: "+v);
                            version = CSVersion.CS4;
                            return version;
                        }
                    }
                }
                catch (Throwable ignore) {}
                finally {
                    APITrace.end();
                }
            }

            versionString = (properties == null ? "CS3" : properties.getProperty("apiVersion", "CS3"));
            try {
                version = CSVersion.valueOf(versionString);
            }
            catch( Throwable t ) {
                version = CSVersion.CS3;
            }
        }
        return version;
    }
    
    public @Nonnegative long parseTime(@Nonnull String timestamp) {
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ"); //2009-02-03T05:26:32.612278
        
        try {
            return df.parse(timestamp).getTime();
        }
        catch( ParseException e ) {
            df = new SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy"); //Sun Jul 04 02:18:02 EST 2010
            
            try {
                return df.parse(timestamp).getTime();
            }
            catch( ParseException another ) {
                return 0L;
            }
        }        
    }
    
    @Override
    public @Nullable String testContext() {
        APITrace.begin(this, "testContext");
        try {
            try {
                ProviderContext ctx = getContext();

                if( ctx == null ) {
                    return null;
                }
                if( logger.isDebugEnabled() ) {
                    logger.debug("testContext(): Checking CSCloud compute credentials");
                }
                if( !isSubscribed() ) {
                    logger.warn("testContext(): CSCloud compute credentials are not subscribed for VM services");
                    return null;
                }
                if( hasStorageServices() ) {
                    if( logger.isDebugEnabled() ) {
                        logger.debug("testContext(): Checking " + ctx.getStorage() + " storage credentials");
                    }
                    StorageServices services = getStorageServices();

                    if( services != null && services.hasBlobStoreSupport() ) {
                        BlobStoreSupport support = services.getBlobStoreSupport();

                        if( support != null ) {
                            try {
                                support.list(null).iterator().hasNext();
                            }
                            catch( Throwable t ) {
                                logger.warn("testContext(): Storage credentials failed: " + t.getMessage());
                                t.printStackTrace();
                                return null;
                            }
                        }
                    }
                }
                if( logger.isInfoEnabled() ) {
                    logger.info("testContext(): Credentials validated");
                }
                return ctx.getAccountNumber();
            }
            catch( Throwable t ) {
                logger.warn("testContext(): Failed to test cloudstack context: " + t.getMessage());
                t.printStackTrace();
                return null;
            }
        }
        finally {
            APITrace.end();
        }
    }

    private boolean isSubscribed() throws CloudException, InternalException {
        APITrace.begin(this, "CSCloud.isSubscribed");
        try {
            CSMethod method = new CSMethod(this);

            try {
                Document doc = method.get(method.buildUrl(LIST_ACCOUNTS), LIST_ACCOUNTS);
                NodeList matches = doc.getElementsByTagName("user");

                if( matches.getLength() < 1 ) {
                    return false;
                }
                String ctxKey = null;
                try {
                    ctxKey = new String(getContext().getAccessPublic(), "utf-8");
                }
                catch( UnsupportedEncodingException e ) {
                    e.printStackTrace();
                    throw new RuntimeException("This cannot happen: " + e.getMessage());
                }

                for( int i=0; i<matches.getLength(); i++ ) {
                    boolean found = false;
                    String account = null;
                    Node node = matches.item(i);
                    NodeList attributes = node.getChildNodes();

                    for (int j = 0; j<attributes.getLength(); j++) {
                        Node attribute = attributes.item(j);
                        String name = attribute.getNodeName().toLowerCase();
                        String value;

                        if( attribute.getChildNodes().getLength() > 0 ) {
                            value = attribute.getFirstChild().getNodeValue();
                        }
                        else {
                            value = null;
                        }
                        if (name.equals("apikey")) {
                            if (value.equals(ctxKey)) {
                                found = true;
                                continue;
                            }
                        }
                        else  if (name.equals("account")) {
                            account = value;
                        }
                    }
                    if (found) {
                        getContext().setAccountNumber(account);
                        return true;
                    }
                }
                logger.debug("No match to api key found");
                return false;
            }
            catch( CSException e ) {
                int code = e.getHttpCode();

                if( code == HttpServletResponse.SC_FORBIDDEN || code == 401 || code == 531 ) {
                    return false;
                }
                throw e;
            }
            catch( CloudException e ) {
                int code = e.getHttpCode();

                if( code == HttpServletResponse.SC_FORBIDDEN || code == HttpServletResponse.SC_UNAUTHORIZED ) {
                    return false;
                }
                throw e;
            }
        }
        finally {
            APITrace.end();
        }
    }

    public Document waitForJob(Document doc, String jobName) throws CloudException, InternalException {
        NodeList matches = doc.getElementsByTagName("jobid");
        if( matches.getLength() > 0 ) {
            return waitForJob(matches.item(0).getFirstChild().getNodeValue(), jobName);
        }    
        return null;
    }
    
    public Document waitForJob(String jobId, String jobName) throws CloudException, InternalException {
        APITrace.begin(this, "waitForJob");
        try {
            CSMethod method = new CSMethod(this);
            String url = method.buildUrl("queryAsyncJobResult", new Param("jobId", jobId));

            while( true ) {
                try { Thread.sleep(5000L); }
                catch( InterruptedException e ) { /* ignore */ }
                Document doc = method.get(url, "queryAsyncJobResult");

                NodeList matches = doc.getElementsByTagName("jobstatus");
                int status = 0;

                if( matches.getLength() > 0 ) {
                    status = Integer.parseInt(matches.item(0).getFirstChild().getNodeValue());
                }
                if( status > 0 ) {
                    int code = status;

                    if( status == 1 ) {
                        return doc;
                    }
                    if( status == 2 ) {
                        matches = doc.getElementsByTagName("jobresult");
                        if( matches.getLength() > 0 ) {
                            String str = matches.item(0).getFirstChild().getNodeValue();

                            if( str == null || str.trim().length() < 1 ) {
                                NodeList nodes = matches.item(0).getChildNodes();
                                String message = null;

                                for( int i=0; i<nodes.getLength(); i++ ) {
                                    Node n = nodes.item(i);

                                    if( n.getNodeName().equalsIgnoreCase("errorcode") ) {
                                        try {
                                            code = Integer.parseInt(n.getFirstChild().getNodeValue().trim());
                                        }
                                        catch( NumberFormatException ignore ) {
                                            // ignore
                                        }
                                    }
                                    else if( n.getNodeName().equalsIgnoreCase("errortext") ) {
                                        message = n.getFirstChild().getNodeValue().trim();
                                    }
                                }
                                CSMethod.ParsedError error = new CSMethod.ParsedError();

                                error.code = code;
                                error.message = message;
                                throw new CSException(error);
                            }
                            else {
                                throw new CloudException(str);
                            }
                        }
                        else {
                            throw new CloudException(jobName + " failed with an unexplained error.");
                        }
                    }
                }
            }
        }
        finally {
            APITrace.end();
        }
    }

    private transient String parentAccount;
    private transient String domainId;

    public String getParentAccount(@Nullable String account) throws CloudException, InternalException {
        if( parentAccount == null ) {
            refreshUserAccountData(account);
        }
        return parentAccount;
    }
    public String getDomainId(@Nullable String account) throws CloudException, InternalException {
        if (domainId == null) {
           refreshUserAccountData(account);
        }
        return domainId;
    }

    public void refreshUserAccountData(@Nullable String account) throws CloudException, InternalException {
        APITrace.begin(this, "refreshAccountData");
        if (account == null) {
            account = getContext().getAccountNumber();
        }

        try {
            CSMethod method = new CSMethod(this);
            String url = method.buildUrl("listAccounts");

            Document doc = method.get(url, "listAccounts");
            NodeList matches = doc.getElementsByTagName("user");

            for (int i = 0; i<matches.getLength(); i++) {
                boolean foundUser = false;
                String accountForUser = null;
                String domainIdForUser = null;
                NodeList attributes = matches.item(i).getChildNodes();

                for( int j=0; j<attributes.getLength(); j++ ) {
                    Node attribute = attributes.item(j);
                    String name = attribute.getNodeName().toLowerCase();
                    String value;

                    if( attribute.hasChildNodes() && attribute.getChildNodes().getLength() > 0 ) {
                        value = attribute.getFirstChild().getNodeValue();
                    }
                    else {
                        value = null;
                    }

                    if (name.equalsIgnoreCase("username")) {
                        if (account.equalsIgnoreCase(value)) {
                            foundUser = true;
                        }
                    }
                    else if (name.equalsIgnoreCase("account")) {
                        accountForUser = value;
                    }
                    else if (name.equalsIgnoreCase("domainid")) {
                        domainIdForUser = value;
                    }
                }
                if (foundUser) {
                    parentAccount = accountForUser;
                    domainId = domainIdForUser;
                    break;
                }
            }
        }
        finally {
            APITrace.end();
        }
    }
}
