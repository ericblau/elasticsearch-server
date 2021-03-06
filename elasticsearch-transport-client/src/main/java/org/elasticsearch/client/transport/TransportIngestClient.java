/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.client.transport;

import org.elasticsearch.action.ActionModule;
import com.google.common.collect.ImmutableList;
import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.action.*;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.get.MultiGetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.percolate.PercolateRequest;
import org.elasticsearch.action.percolate.PercolateResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.GenericClient;
import org.elasticsearch.client.support.AbstractIngestClient;
import org.elasticsearch.client.transport.support.InternalTransportIngestClient;
import org.elasticsearch.cluster.ClusterNameModule;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.CacheRecycler;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.compress.CompressorFactory;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.ModulesBuilder;
import org.elasticsearch.common.io.CachedStreams;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsModule;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.util.concurrent.ThreadLocals;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.EnvironmentModule;
import org.elasticsearch.monitor.MonitorService;
import org.elasticsearch.node.internal.InternalSettingsPerparer;
import org.elasticsearch.plugins.PluginsModule;
import org.elasticsearch.plugins.PluginsService;
import org.elasticsearch.search.TransportSearchModule;
import org.elasticsearch.threadpool.ThreadPoolModule;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportModule;
import org.elasticsearch.transport.TransportService;

import java.util.concurrent.TimeUnit;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.delete.DeleteRequestBuilder;
import org.elasticsearch.action.get.GetRequestBuilder;
import org.elasticsearch.action.get.MultiGetRequestBuilder;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.percolate.PercolateRequestBuilder;
import org.elasticsearch.action.update.UpdateRequestBuilder;

import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;
import org.elasticsearch.threadpool.server.ServerThreadPool;

/**
 * The transport client allows to create a client that is not part of the cluster, but simply connects to one
 * or more nodes directly by adding their respective addresses using {@link #addTransportAddress(org.elasticsearch.common.transport.TransportAddress)}.
 * <p/>
 * <p>The transport client important modules used is the {@link org.elasticsearch.transport.TransportModule} which is
 * started in client mode (only connects, no bind).
 */
public class TransportIngestClient extends AbstractIngestClient {

    private final Injector injector;

    private final Settings settings;

    private final Environment environment;

    private final PluginsService pluginsService;

    private final TransportClientNodesService nodesService;

    private final InternalTransportIngestClient internalClient;
    
    private final ThreadPool threadPool;


    /**
     * Constructs a new transport client with settings loaded either from the classpath or the file system (the
     * <tt>elasticsearch.(yml|json)</tt> files optionally prefixed with <tt>config/</tt>).
     */
    public TransportIngestClient() throws ElasticSearchException {
        this(ImmutableSettings.Builder.EMPTY_SETTINGS, true);
    }

    /**
     * Constructs a new transport client with explicit settings and settings loaded either from the classpath or the file
     * system (the <tt>elasticsearch.(yml|json)</tt> files optionally prefixed with <tt>config/</tt>).
     */
    public TransportIngestClient(Settings settings) {
        this(settings, true);
    }

    /**
     * Constructs a new transport client with explicit settings and settings loaded either from the classpath or the file
     * system (the <tt>elasticsearch.(yml|json)</tt> files optionally prefixed with <tt>config/</tt>).
     */
    public TransportIngestClient(Settings.Builder settings) {
        this(settings.build(), true);
    }

    /**
     * Constructs a new transport client with the provided settings and the ability to control if settings will
     * be loaded from the classpath / file system (the <tt>elasticsearch.(yml|json)</tt> files optionally prefixed with
     * <tt>config/</tt>).
     *
     * @param settings           The explicit settings.
     * @param loadConfigSettings <tt>true</tt> if settings should be loaded from the classpath/file system.
     * @throws ElasticSearchException
     */
    public TransportIngestClient(Settings.Builder settings, boolean loadConfigSettings) throws ElasticSearchException {
        this(settings.build(), loadConfigSettings);
    }

    /**
     * Constructs a new transport client with the provided settings and the ability to control if settings will
     * be loaded from the classpath / file system (the <tt>elasticsearch.(yml|json)</tt> files optionally prefixed with
     * <tt>config/</tt>).
     *
     * @param pSettings          The explicit settings.
     * @param loadConfigSettings <tt>true</tt> if settings should be loaded from the classpath/file system.
     * @throws ElasticSearchException
     */
    public TransportIngestClient(Settings pSettings, boolean loadConfigSettings) throws ElasticSearchException {
        Tuple<Settings, Environment> tuple = InternalSettingsPerparer.prepareSettings(pSettings, loadConfigSettings);
        Settings settings = settingsBuilder().put(tuple.v1())
                .put("network.server", false)
                .put("node.client", true)
                .build();
        this.environment = tuple.v2();

        this.pluginsService = new PluginsService(tuple.v1(), tuple.v2());
        settings = pluginsService.updatedSettings();
        this.settings = settings;

        CompressorFactory.configure(settings);

        ModulesBuilder modules = new ModulesBuilder();
        modules.add(new PluginsModule(settings, pluginsService));
        modules.add(new EnvironmentModule(environment));
        modules.add(new SettingsModule(settings));
        modules.add(new NetworkModule());
        modules.add(new ClusterNameModule(settings));
        modules.add(new ThreadPoolModule(settings));
        modules.add(new TransportSearchModule());
        modules.add(new TransportModule(settings));
        modules.add(new ActionModule(true));
        modules.add(new ClientTransportModule());

        injector = modules.createInjector();

        injector.getInstance(TransportService.class).start();

        nodesService = injector.getInstance(TransportClientNodesService.class);
        threadPool = injector.getInstance(ServerThreadPool.class);
        internalClient = injector.getInstance(InternalTransportIngestClient.class);
    }

    /**
     * Returns the current registered transport addresses to use (added using
     * {@link #addTransportAddress(org.elasticsearch.common.transport.TransportAddress)}.
     */
    public ImmutableList<TransportAddress> transportAddresses() {
        return nodesService.transportAddresses();
    }

    /**
     * Returns the current connected transport nodes that this client will use.
     * <p/>
     * <p>The nodes include all the nodes that are currently alive based on the transport
     * addresses provided.
     */
    public ImmutableList<DiscoveryNode> connectedNodes() {
        return nodesService.connectedNodes();
    }

    /**
     * Returns the listed nodes in the transport client (ones added to it).
     */
    public ImmutableList<DiscoveryNode> listedNodes() {
        return nodesService.listedNodes();
    }

    /**
     * Adds a transport address that will be used to connect to.
     * <p/>
     * <p>The Node this transport address represents will be used if its possible to connect to it.
     * If it is unavailable, it will be automatically connected to once it is up.
     * <p/>
     * <p>In order to get the list of all the current connected nodes, please see {@link #connectedNodes()}.
     */
    public TransportIngestClient addTransportAddress(TransportAddress transportAddress) {
        nodesService.addTransportAddresses(transportAddress);
        return this;
    }

    /**
     * Adds a list of transport addresses that will be used to connect to.
     * <p/>
     * <p>The Node this transport address represents will be used if its possible to connect to it.
     * If it is unavailable, it will be automatically connected to once it is up.
     * <p/>
     * <p>In order to get the list of all the current connected nodes, please see {@link #connectedNodes()}.
     */
    public TransportIngestClient addTransportAddresses(TransportAddress... transportAddress) {
        nodesService.addTransportAddresses(transportAddress);
        return this;
    }

    /**
     * Removes a transport address from the list of transport addresses that are used to connect to.
     */
    public TransportIngestClient removeTransportAddress(TransportAddress transportAddress) {
        nodesService.removeTransportAddress(transportAddress);
        return this;
    }

    @Override
    public ThreadPool threadPool() {
        return threadPool;
    }
    
    /**
     * Closes the client.
     */
    @Override
    public void close() {
        injector.getInstance(TransportClientNodesService.class).close();
        injector.getInstance(TransportService.class).close();
        try {
            injector.getInstance(MonitorService.class).close();
        } catch (Exception e) {
            // ignore, might not be bounded
        }

        for (Class<? extends LifecycleComponent> plugin : pluginsService.services()) {
            injector.getInstance(plugin).close();
        }

        threadPool.shutdown();
        try {
            threadPool.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // ignore
        }
        try {
            threadPool.shutdownNow();
        } catch (Exception e) {
            // ignore
        }

        CacheRecycler.clear();
        CachedStreams.clear();
        ThreadLocals.clearReferencesThreadLocals();
    }

    @Override
    public Settings settings() {
        return this.settings;
    }

    @Override
    public <Request extends ActionRequest, Response extends ActionResponse, RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder>, IngestClient extends GenericClient> 
            ActionFuture<Response> execute(Action<Request, Response, RequestBuilder, IngestClient> action, Request request) {
        return internalClient.execute(action, request);
    }

    @Override
    public <Request extends ActionRequest, Response extends ActionResponse, RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder>, IngestClient extends GenericClient> 
            void execute(Action<Request, Response, RequestBuilder, IngestClient> action, Request request, ActionListener<Response> listener) {
        internalClient.execute(action, request, listener);
    }

    @Override
    public ActionFuture<IndexResponse> index(IndexRequest request) {
        return internalClient.index(request);
    }

    @Override
    public void index(IndexRequest request, ActionListener<IndexResponse> listener) {
        internalClient.index(request, listener);
    }

    @Override
    public ActionFuture<UpdateResponse> update(UpdateRequest request) {
        return internalClient.update(request);
    }

    @Override
    public void update(UpdateRequest request, ActionListener<UpdateResponse> listener) {
        internalClient.update(request, listener);
    }

    @Override
    public ActionFuture<DeleteResponse> delete(DeleteRequest request) {
        return internalClient.delete(request);
    }

    @Override
    public void delete(DeleteRequest request, ActionListener<DeleteResponse> listener) {
        internalClient.delete(request, listener);
    }

    @Override
    public ActionFuture<BulkResponse> bulk(BulkRequest request) {
        return internalClient.bulk(request);
    }

    @Override
    public void bulk(BulkRequest request, ActionListener<BulkResponse> listener) {
        internalClient.bulk(request, listener);
    }

    @Override
    public ActionFuture<GetResponse> get(GetRequest request) {
        return internalClient.get(request);
    }

    @Override
    public void get(GetRequest request, ActionListener<GetResponse> listener) {
        internalClient.get(request, listener);
    }

    @Override
    public ActionFuture<MultiGetResponse> multiGet(MultiGetRequest request) {
        return internalClient.multiGet(request);
    }

    @Override
    public void multiGet(MultiGetRequest request, ActionListener<MultiGetResponse> listener) {
        internalClient.multiGet(request, listener);
    }

    @Override
    public ActionFuture<PercolateResponse> percolate(PercolateRequest request) {
        return internalClient.percolate(request);
    }

    @Override
    public void percolate(PercolateRequest request, ActionListener<PercolateResponse> listener) {
        internalClient.percolate(request, listener);
    }


    @Override
    public IndexRequestBuilder prepareIndex() {
        return internalClient.prepareIndex();
    }

    @Override
    public UpdateRequestBuilder prepareUpdate() {
        return internalClient.prepareUpdate();
    }

    @Override
    public UpdateRequestBuilder prepareUpdate(String index, String type, String id) {
        return internalClient.prepareUpdate(index, type, id);
    }

    @Override
    public IndexRequestBuilder prepareIndex(String index, String type) {
        return internalClient.prepareIndex(index, type);
    }

    @Override
    public IndexRequestBuilder prepareIndex(String index, String type, String id) {
        return internalClient.prepareIndex(index, type, id);
    }

    @Override
    public DeleteRequestBuilder prepareDelete() {
        return internalClient.prepareDelete();
    }

    @Override
    public DeleteRequestBuilder prepareDelete(String index, String type, String id) {
        return internalClient.prepareDelete(index, type, id);
    }

    @Override
    public BulkRequestBuilder prepareBulk() {
        return internalClient.prepareBulk();
    }

    @Override
    public GetRequestBuilder prepareGet() {
        return internalClient.prepareGet();
    }

    @Override
    public GetRequestBuilder prepareGet(String index, String type, String id) {
        return internalClient.prepareGet(index, type, id);
    }

    @Override
    public MultiGetRequestBuilder prepareMultiGet() {
        return internalClient.prepareMultiGet();
    }

    @Override
    public PercolateRequestBuilder preparePercolate(String index, String type) {
        return internalClient.preparePercolate(index, type);
    }

}
