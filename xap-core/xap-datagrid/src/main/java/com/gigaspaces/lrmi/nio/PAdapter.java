/*
 * Copyright (c) 2008-2016, GigaSpaces Technologies, Inc. All Rights Reserved.
 *
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
 */

package com.gigaspaces.lrmi.nio;

import com.gigaspaces.config.ConfigurationException;
import com.gigaspaces.config.lrmi.ITransportConfig;
import com.gigaspaces.config.lrmi.nio.NIOConfiguration;
import com.gigaspaces.internal.lrmi.LRMIMonitoringDetailsImpl;
import com.gigaspaces.internal.utils.concurrent.GSThread;
import com.gigaspaces.internal.version.PlatformLogicalVersion;
import com.gigaspaces.logger.Constants;
import com.gigaspaces.lrmi.ClientPeerInvocationHandler;
import com.gigaspaces.lrmi.ConnPoolInvocationHandler;
import com.gigaspaces.lrmi.ConnectionPool;
import com.gigaspaces.lrmi.DynamicSmartStub;
import com.gigaspaces.lrmi.GenericExporter;
import com.gigaspaces.lrmi.LRMIRuntime;
import com.gigaspaces.lrmi.ProtocolAdapter;
import com.gigaspaces.lrmi.ServerPeer;
import com.gigaspaces.lrmi.classloading.DefaultClassProvider;
import com.gigaspaces.lrmi.classloading.IClassProvider;
import com.gigaspaces.lrmi.nio.selector.handler.client.ClientConversationRunner;
import com.gigaspaces.lrmi.nio.selector.handler.client.ClientHandler;
import com.gigaspaces.management.transport.ITransportConnection;
import com.j_spaces.core.service.ServiceConfigLoader;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.rmi.RemoteException;
import java.rmi.server.ExportException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An NIO based implementation of LRMI Protocol Adapter
 *
 * The basic design is as follows:
 *
 * There will be a single 'pivot' object at the server side, that will act as a local proxy for all
 * server peers in the server JVM. The pivot is in charge of all connection management and
 * invocation dispatching. See Pivot documentation for more details.
 *
 * @author Igor Goldenberg
 * @since 4.0
 */
@com.gigaspaces.api.InternalApi
public class PAdapter implements ProtocolAdapter<CPeer> {
    //logger
    final private static Logger _logger = Logger.getLogger(Constants.LOGGER_LRMI);

    final static String ADAPTER_NAME = "NIO";

    private Pivot m_Pivot;
    @SuppressWarnings("FieldCanBeLocal")
    private NIOConfiguration _nioConfig;
    private GenericExporter _exporter;
    private IClassProvider _classProvider;

    private ClientHandler[] _handlers;
    private ClientConversationRunner _clientConversationRunner;
    private final AtomicInteger nextIndex = new AtomicInteger(0);

    private volatile boolean _clientSideInitialized = false;
    private volatile boolean _serverSideInitialized = false;

    private boolean _shutdown = false;

    public PAdapter() {
    }

    synchronized public void init(ITransportConfig config, ProtocolAdapter.Side initSide)
            throws RemoteException, ConfigurationException {
        if (!_shutdown) {
            NIOConfiguration nioConfig = (NIOConfiguration) config;
            if (initSide == ProtocolAdapter.Side.CLIENT) {
                clientSideInit();
            } else {
                serverSideInit(nioConfig);
            }
        }
    }

    private void clientSideInit() throws RemoteException {
        if (_clientSideInitialized)
            return;

        _clientSideInitialized = true;
        _exporter = (GenericExporter) ServiceConfigLoader.getExporter();
        initClassProvider();

        _handlers = new ClientHandler[4];
        for (int i = 0; i < _handlers.length; i++) {
            try {
                _handlers[i] = new ClientHandler();
                GSThread writeThread = new GSThread(_handlers[i], "LRMI-async-Selector-Thread-" + i);
                writeThread.setDaemon(true);
                writeThread.start();
            } catch (IOException e) {
                _logger.log(Level.SEVERE, "cant create a selector for async calls", e);
                throw new IllegalStateException("cant create a selector for async calls", e);
            }
        }
        try {
            _clientConversationRunner = new ClientConversationRunner();
            GSThread writeThread = new GSThread(_clientConversationRunner, "LRMI-async-client-connection-Thread-");
            writeThread.setDaemon(true);
            writeThread.start();
        } catch (IOException e) {
            _logger.log(Level.SEVERE, "cant create a selector for connect", e);
            throw new IllegalStateException("cant create a selector for connect", e);
        }
    }

    private synchronized void initClassProvider() throws ExportException {
        if (_classProvider == null) {
            DefaultClassProvider provider = new DefaultClassProvider(String.valueOf(LRMIRuntime.getRuntime().getID()));
            _classProvider = (IClassProvider) _exporter.export(provider, false);
        }
    }

    private void serverSideInit(NIOConfiguration config) throws RemoteException {
        if (_serverSideInitialized)
            return;

        _nioConfig = config;

        _exporter = (GenericExporter) ServiceConfigLoader.getExporter();
        initClassProvider();

        if (_logger.isLoggable(Level.CONFIG))
            _logger.config(config.toString());

        try {
            // creates Pivot on specified according to nioConfig
            m_Pivot = new Pivot(_nioConfig, this);
        } catch (java.io.IOException ex) {
            throw new RemoteException("Failed initialization of LRMI over NIO Protocol Adapter.", ex);
        }

        _serverSideInitialized = true;
    }

    private synchronized ClientHandler getClientHandler() {
        if (_handlers == null)
            throw new IllegalStateException("attempt to getClientHandler but handlers are not initialized, client side initialized state = " + _clientSideInitialized);

        return _handlers[nextIndex.getAndIncrement() % _handlers.length];
    }

    private ClientConversationRunner getClientConversationRunner() {
        return _clientConversationRunner;
    }

    public IClassProvider getClassProvider() {
        return _classProvider;
    }

    public int getPort() {
        return m_Pivot.getPort();
    }

    public String getHostName() {
        return m_Pivot.getHostName();
    }

    public String getName() {
        return ADAPTER_NAME;
    }


    /**
     * @return the INetSocketAddress this ProtocolAdapter bind to.
     */
    public InetSocketAddress getBindInetSocketAddress() {
        return m_Pivot.getServerBindInetSocketAddress();
    }

    public CPeer getClientPeer(PlatformLogicalVersion serviceVersion) {
        return new CPeer(this, getClientHandler(), getClientConversationRunner(), serviceVersion);
    }

    public ServerPeer newServerPeer(long objectId, ClassLoader objectClassLoader, String serviceDetails) {
        return new SPeer(this, objectId, objectClassLoader, serviceDetails);
    }

    // TODO Igor.G 15/1/07
    // make pivot accessible outside of NIO package just for management support
    // After cleanup this method need back to be package access
    public Pivot getPivot() {
        return m_Pivot;
    }

    /*
    * @see com.j_spaces.kernel.lrmi.ProtocolAdapter#shutdown()
    */
    synchronized public void shutdown() {
        _shutdown = true;
        if (_handlers != null) {
            for (ClientHandler handler : _handlers) {
                if (handler != null) // in case new ClientHandler() failed because user interrupt thread.
                    handler.shutdown();
            }
        }

        if (m_Pivot != null) {
            m_Pivot.shutdown();
        }

        _exporter.unexport(_classProvider);
    }

    public List<ITransportConnection> getRemoteObjectConnectionsList(long objectId) {
        return m_Pivot.getRemoteObjectConnectionsList(objectId);
    }

    public int countRemoteObjectConnections(long objectId) {
        return m_Pivot.countRemoteObjectConnections(objectId);
    }

    @Override
    public ClientPeerInvocationHandler getClientInvocationHandler(String connectionURL, ITransportConfig config, PlatformLogicalVersion serviceVersion) {
        /* create a connection pool and appropriate invocation handler */
        ConnectionPool connPool = new ConnectionPool(this, config, connectionURL, serviceVersion);
        return new ConnPoolInvocationHandler(connPool);
    }

    @Override
    public LRMIMonitoringDetailsImpl getMonitoringDetails() {
        return new LRMIMonitoringDetailsImpl(m_Pivot.getMonitoringDetails(), DynamicSmartStub.getMonitoringDetails());
    }
}