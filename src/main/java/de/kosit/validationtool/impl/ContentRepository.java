/*
 * Licensed to the Koordinierungsstelle für IT-Standards (KoSIT) under
 * one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  KoSIT licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package de.kosit.validationtool.impl;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.xml.transform.Source;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.SAXException;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import de.kosit.validationtool.impl.Scenario.Transformation;
import de.kosit.validationtool.model.scenarios.NamespaceType;
import de.kosit.validationtool.model.scenarios.ResourceType;
import de.kosit.validationtool.model.scenarios.ScenarioType;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XPathCompiler;
import net.sf.saxon.s9api.XPathExecutable;
import net.sf.saxon.s9api.XsltCompiler;
import net.sf.saxon.s9api.XsltExecutable;

/**
 * Repository für verschiedene XML Artefakte zur Vearbeitung der Prüfszenarien.
 * 
 * @author Andreas Penski
 */
@RequiredArgsConstructor
@Slf4j
public class ContentRepository {

    private Schema reportInputSchema;

    @Getter
    private final Processor processor;

    private final URI repository;

    private final ResolvingMode mode = ResolvingMode.STRICT_RELATIVE;

    private Source resolve(final URL resource) {
        try {
            return new StreamSource(resource.openStream(), resource.toURI().getRawPath());
        } catch (final IOException | URISyntaxException e) {
            throw new IllegalStateException("Can not load schema for resource " + resource.getPath(), e);
        }
    }

    private Schema createSchema(final Source[] schemaSources, final LSResourceResolver resourceResolver) {
        try {
            final SchemaFactory sf = ObjectFactory.createSchemaFactory();
            sf.setResourceResolver(resourceResolver);
            return sf.newSchema(schemaSources);
        } catch (final SAXException e) {
            throw new IllegalArgumentException("Can not load schema from sources " + schemaSources[0].getSystemId(), e);
        }
    }

    private Schema createSchema(final Source[] schemaSources) {
        return createSchema(schemaSources, null);
    }

    /**
     * Lädt ein XSL von der angegebenen URI
     *
     * @param uri die URI der XSL Definition
     * @return ein XSLT Executable
     */
    public XsltExecutable loadXsltScript(final URI uri) {
        log.info("Loading XSLT script from  {}", uri);
        final XsltCompiler xsltCompiler = getProcessor().newXsltCompiler();
        final CollectingErrorEventHandler listener = new CollectingErrorEventHandler();
        try {
            xsltCompiler.setErrorListener(listener);
            xsltCompiler.setURIResolver(createResolver());

            return xsltCompiler.compile(resolve(uri));
        } catch (final SaxonApiException e) {
            listener.getErrors().forEach(event -> event.log(log));
            throw new IllegalStateException("Can not compile xslt executable for uri " + uri, e);
        } finally {
            if (!listener.hasErrors() && listener.hasEvents()) {
                log.warn("Received warnings while loading a xslt script {}", uri);
                listener.getErrors().forEach(e -> e.log(log));
            }
        }
    }

    /**
     * Erzeugt ein Schema-Objekt auf Basis der übergebenen URL.
     *
     * @param url die url
     * @return das erzeugte Schema
     */
    public Schema createSchema(final URL url) {
        return createSchema(url, null);
    }

    public Schema createSchema(final URL url, final LSResourceResolver resourceResolver) {
        log.info("Load schema from source {}", url.getPath());
        return createSchema(new Source[] { resolve(url) }, resourceResolver);
    }

    /**
     * Liefert das definiert Schema für die Szenario-Konfiguration
     *
     * @return Scenario-Schema
     */
    public Schema getScenarioSchema() {
        return createSchema(ContentRepository.class.getResource("/xsd/scenarios.xsd"));
    }

    /**
     * Liefert das definierte Schema für die Validierung des [@link CreateReportInput}
     *
     * @return ReportInput-Schema
     */
    public Schema getReportInputSchema() {
        if (reportInputSchema == null) {
            final Source source = resolve(ContentRepository.class.getResource("/xsd/createReportInput.xsd"));
            reportInputSchema = createSchema(new Source[] { source }, new ClassPathResourceResolver("/xsd"));
        }
        return reportInputSchema;
    }

    /**
     * Erzeugt ein Schema auf Basis der übegebenen URIs
     * 
     * @param uris die uris in String-Repräsentation
     * @return das Schema
     */
    public Schema createSchema(final Collection<String> uris) {
        return createSchema(uris.stream().map(s -> resolve(URI.create(s))).toArray(Source[]::new));
    }

    /**
     * Liefert das Schema zu diesem Szenario.
     *
     * @return das passende Schema
     */
    public Schema createSchema(final ScenarioType s) {
        Schema schema = null;
        if (s.getValidateWithXmlSchema() != null) {
            final List<String> schemaResources = s.getValidateWithXmlSchema().getResource().stream().map(ResourceType::getLocation)
                    .collect(Collectors.toList());
            schema = createSchema(schemaResources);
        }
        return schema;
    }

    private Source resolve(final URI source) {
        final URI resolved = this.mode.resolve(source, this.repository);
        return new StreamSource(resolved.toASCIIString());
    }

    /**
     * Erzeugt einen [@link XPathExecutable} auf Basis der angegebenen Informationen.
     * 
     * @param expression der XPATH-Ausdruck
     * @param namespaces optionale Namespace-Mappings
     * @return ein kompiliertes Executable
     */
    public XPathExecutable createXPath(final String expression, final Map<String, String> namespaces) {
        try {
            final XPathCompiler compiler = getProcessor().newXPathCompiler();
            if (namespaces != null) {
                namespaces.forEach(compiler::declareNamespace);
            }
            return compiler.compile(expression);
        } catch (final SaxonApiException e) {
            throw new IllegalStateException(String.format("Can not compile xpath match expression '%s'",
                    StringUtils.isNotBlank(expression) ? expression : "EMPTY EXPRESSION"), e);
        }
    }

    /**
     * Erzeugt einen resolver für dieses Repository, der nur relativ auflösen kann
     * 
     * @return ein neuer Resolver
     */
    public URIResolver createResolver() {
        return this.mode.createResolver(this.repository);
    }

    /**
     * Gibt eine Transformation zurück.
     *
     * @return initialisierte Transformation
     */
    public Transformation createReportTransformation(final ScenarioType t) {
        final ResourceType resource = t.getCreateReport().getResource();
        final XsltExecutable executable = loadXsltScript(URI.create(resource.getLocation()));
        return new Transformation(executable, resource);
    }

    public XPathExecutable createMatchExecutable(final ScenarioType s) {
        final Map<String, String> namespaces = s.getNamespace().stream()
                .collect(Collectors.toMap(NamespaceType::getPrefix, NamespaceType::getValue));
        return createXPath(s.getMatch(), namespaces);
    }

    public XPathExecutable createAccepptExecutable(final ScenarioType s) {
        final Map<String, String> namespaces = s.getNamespace().stream()
                .collect(Collectors.toMap(NamespaceType::getPrefix, NamespaceType::getValue));
        return createXPath(s.getAcceptMatch(), namespaces);
    }
}
