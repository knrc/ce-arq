/*
 * JBoss, Home of Professional Open Source
 * Copyright 2015 Red Hat Inc. and/or its affiliates and other
 * contributors as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.arquillian.ce.protocol;

import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.util.Collection;
import java.util.List;
import java.util.Timer;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.internal.com.ning.http.client.AsyncHttpClient;
import io.fabric8.kubernetes.client.internal.com.ning.http.client.ListenableFuture;
import io.fabric8.kubernetes.client.internal.com.ning.http.client.Response;
import org.jboss.arquillian.ce.utils.Strings;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.container.test.spi.command.CommandCallback;
import org.jboss.arquillian.protocol.servlet.ServletMethodExecutor;
import org.jboss.arquillian.test.spi.TestMethodExecutor;
import org.jboss.arquillian.test.spi.TestResult;

/**
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class CEServletExecutor extends ServletMethodExecutor {
    private static final String PROXY_URL = "%s/api/%s/namespaces/%s/pods/%s/proxy" + "%s?port=8080&%s";

    private String contextRoot;
    private KubernetesClient client;

    public CEServletExecutor(CEProtocolConfiguration configuration, ProtocolMetaData protocolMetaData, CommandCallback callback) {
        this.config = configuration;
        this.callback = callback;

        this.contextRoot = readContextRoot(protocolMetaData);

        Config config = new ConfigBuilder().withMasterUrl(configuration.getKubernetesMaster()).build();
        this.client = new DefaultKubernetesClient(config);
    }

    private String readContextRoot(ProtocolMetaData protocolMetaData) {
        Collection<HTTPContext> contexts = protocolMetaData.getContexts(HTTPContext.class);
        for (HTTPContext context : contexts) {
            Servlet arqServlet = context.getServletByName(ARQUILLIAN_SERVLET_NAME);
            if (arqServlet != null) {
                return arqServlet.getContextRoot();
            }
        }
        throw new IllegalArgumentException("No Arquillian servlet in HTTPContext meta data!");
    }

    private CEProtocolConfiguration config() {
        return CEProtocolConfiguration.class.cast(config);
    }

    public TestResult invoke(final TestMethodExecutor testMethodExecutor) {
        if (testMethodExecutor == null) {
            throw new IllegalArgumentException("TestMethodExecutor must be specified");
        }

        Class<?> testClass = testMethodExecutor.getInstance().getClass();

        String host = config().getKubernetesMaster();
        String version = config().getApiVersion();
        String namespace = config().getNamespace();
        String pod = locatePod(testMethodExecutor);

        String url = String.format(PROXY_URL, host, version, namespace, pod, contextRoot + ARQUILLIAN_SERVLET_MAPPING, "outputMode=serializedObject&className=" + testClass.getName() + "&methodName=" + testMethodExecutor.getMethod().getName());
        String eventUrl = String.format(PROXY_URL, host, version, namespace, pod, contextRoot + ARQUILLIAN_SERVLET_MAPPING, "outputMode=serializedObject&className=" + testClass.getName() + "&methodName=" + testMethodExecutor.getMethod().getName() + "&cmd=event");

        Timer eventTimer = null;
        try {
            eventTimer = createCommandServicePullTimer(eventUrl);
            return executeWithRetry(url, TestResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Error launching test " + testClass.getName() + " "
                + testMethodExecutor.getMethod(), e);
        } finally {
            if (eventTimer != null) {
                eventTimer.cancel();
            }
        }
    }

    protected <T> T execute(String url, Class<T> returnType, Object requestObject) throws Exception {
        final AsyncHttpClient httpClient = client.getHttpClient();

        AsyncHttpClient.BoundRequestBuilder builder = httpClient.preparePost(url);

        if (requestObject != null) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            try {
                oos.writeObject(requestObject);
            } catch (Exception e) {
                throw new RuntimeException("Error sending request Object, " + requestObject, e);
            } finally {
                oos.flush();
                oos.close();
            }
            builder.setBody(baos.toByteArray());
        }

        ListenableFuture<Response> future = builder.execute();
        Response response = future.get();

        int responseCode = response.getStatusCode();

        if (responseCode == HttpURLConnection.HTTP_OK) {
            Object o;
            try (ObjectInputStream ois = new ObjectInputStream(response.getResponseBodyAsStream())) {
                o = ois.readObject();
            }

            if (returnType.isInstance(o) == false) {
                throw new IllegalStateException("Error reading results, expected a " + returnType.getName() + " but got " + o);
            }

            return returnType.cast(o);
        } else if (responseCode == HttpURLConnection.HTTP_NO_CONTENT) {
            return null;
        } else if (responseCode != HttpURLConnection.HTTP_NOT_FOUND) {
            throw new IllegalStateException("Error launching test at " + url + ". " + "Got " + responseCode + " (" + response.getStatusText() + ")");
        }

        return null; // TODO
    }

    private String locatePod(TestMethodExecutor testMethodExecutor) {
        Method method = testMethodExecutor.getMethod();
        TargetsContainer tc = method.getAnnotation(TargetsContainer.class);
        int index = 0;
        if (tc != null) {
            String value = tc.value();
            index = Strings.parseNumber(value);
        }
        List<Pod> items = client.pods().inNamespace(config().getNamespace()).list().getItems();
        if (index >= items.size()) {
            throw new IllegalStateException(String.format("Not enough pods (%s) to invoke pod %s!", items.size(), index));
        }
        return items.get(index).getMetadata().getName();
    }
}
