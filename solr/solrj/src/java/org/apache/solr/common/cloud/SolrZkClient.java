/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.common.cloud;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.AlreadyClosedException;
import org.apache.solr.common.ParWork;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.StringUtils;
import org.apache.solr.common.cloud.ConnectionManager.IsClosed;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.common.util.ObjectReleaseTracker;
import org.apache.solr.common.util.SolrNamedThreadFactory;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoAuthException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.eclipse.jetty.io.RuntimeIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * All Solr ZooKeeper interactions should go through this class rather than
 * ZooKeeper. This class handles synchronous connects and reconnections.
 *
 */
public class SolrZkClient implements Closeable {
  private static final int MAX_BYTES_FOR_ZK_LAYOUT_DATA_SHOW = 750;

  static final String NEWL = System.getProperty("line.separator");

  static final int DEFAULT_CLIENT_CONNECT_TIMEOUT = 30000;

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final int zkClientConnectTimeout;

  private final ZkClientConnectionStrategy strat;

  private volatile ConnectionManager connManager;

  private volatile SolrZooKeeper keeper;

  private volatile ZkCmdExecutor zkCmdExecutor;

  private final ExecutorService zkCallbackExecutor =
          new ThreadPoolExecutor(1, 1,
                  3L, TimeUnit.SECONDS,
                  new ArrayBlockingQueue<>(120), // size?
                  new ThreadFactory() {
                    AtomicInteger threadNumber = new AtomicInteger(1);
                    ThreadGroup group;

                    {
                      SecurityManager s = System.getSecurityManager();
                      group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
                    }

                    @Override
                    public Thread newThread(Runnable r) {
                      Thread t = new Thread(group, r, "ZkCallback" + threadNumber.getAndIncrement(), 0);
                      t.setDaemon(false);
                      // t.setPriority(priority);
                      return t;
                    }
                  }, new RejectedExecutionHandler() {

            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
              log.warn("Task was rejected, running in caller thread");
              if (executor.isShutdown() || executor.isTerminated() || executor.isTerminating()) {
                throw new AlreadyClosedException();
              }
              r.run();
            }
          });

  private final ExecutorService zkConnManagerCallbackExecutor =
      ExecutorUtil.newMDCAwareSingleThreadExecutor(new SolrNamedThreadFactory("zkConnectionManagerCallback"));

  private volatile boolean isClosed = false;
  private volatile ZkClientConnectionStrategy zkClientConnectionStrategy;
  private volatile int zkClientTimeout;
  private volatile ZkACLProvider zkACLProvider;
  private volatile String zkServerAddress;
  private volatile IsClosed higherLevelIsClosed;

  public int getZkClientTimeout() {
    return zkClientTimeout;
  }

  public int getZkClientConnectTimeout() {
    return zkClientConnectTimeout;
  }

  // expert: for tests
  public SolrZkClient() {
    zkClientConnectTimeout = 0;
    strat = null;
  }

  public SolrZkClient(String zkServerAddress, int zkClientTimeout) {
    this(zkServerAddress, zkClientTimeout, new DefaultConnectionStrategy(), null);
  }

  public SolrZkClient(String zkServerAddress, int zkClientTimeout, int zkClientConnectTimeout) {
    this(zkServerAddress, zkClientTimeout, zkClientConnectTimeout, new DefaultConnectionStrategy(), null);
  }

  public SolrZkClient(String zkServerAddress, int zkClientTimeout, int zkClientConnectTimeout, OnReconnect onReonnect) {
    this(zkServerAddress, zkClientTimeout, zkClientConnectTimeout, new DefaultConnectionStrategy(), onReonnect);
  }

  public SolrZkClient(String zkServerAddress, int zkClientTimeout,
      ZkClientConnectionStrategy strat, final OnReconnect onReconnect) {
    this(zkServerAddress, zkClientTimeout, DEFAULT_CLIENT_CONNECT_TIMEOUT, strat, onReconnect);
  }

  public SolrZkClient(String zkServerAddress, int zkClientTimeout, int clientConnectTimeout,
      ZkClientConnectionStrategy strat, final OnReconnect onReconnect) {
    this(zkServerAddress, zkClientTimeout, clientConnectTimeout, strat, onReconnect, null, null, null);
  }

  public SolrZkClient(String zkServerAddress, int zkClientTimeout, int clientConnectTimeout,
      ZkClientConnectionStrategy strat, final OnReconnect onReconnect, BeforeReconnect beforeReconnect) {
    this(zkServerAddress, zkClientTimeout, clientConnectTimeout, strat, onReconnect, beforeReconnect, null, null);
  }

  public SolrZkClient(String zkServerAddress, int zkClientTimeout, int clientConnectTimeout,
      ZkClientConnectionStrategy strat, final OnReconnect onReconnect, BeforeReconnect beforeReconnect, ZkACLProvider zkACLProvider, IsClosed higherLevelIsClosed) {

    this.zkServerAddress = zkServerAddress;
    this.higherLevelIsClosed = higherLevelIsClosed;
    if (strat == null) {
      strat = new DefaultConnectionStrategy();
    }
    this.zkClientConnectionStrategy = strat;
    this.zkClientConnectTimeout = clientConnectTimeout;
    if (!strat.hasZkCredentialsToAddAutomatically()) {
      ZkCredentialsProvider zkCredentialsToAddAutomatically = createZkCredentialsToAddAutomatically();
      strat.setZkCredentialsToAddAutomatically(zkCredentialsToAddAutomatically);
    }

    this.zkClientTimeout = zkClientTimeout;
    // we must retry at least as long as the session timeout
    zkCmdExecutor = new ZkCmdExecutor(3000, new IsClosed() {

      @Override
      public boolean isClosed() {
        return SolrZkClient.this.isClosed() || SolrZkClient.this.connManager.isLikelyExpired();
      }
    });
    connManager = new ConnectionManager("ZooKeeperConnection Watcher:"
        + zkServerAddress, this, zkServerAddress, strat, onReconnect, beforeReconnect, new IsClosed() {

          @Override
          public boolean isClosed() {
            return SolrZkClient.this.isClosed();
          }
        });

    try {
      strat.connect(zkServerAddress, zkClientTimeout, wrapWatcher(connManager),
          zooKeeper -> {
            SolrZooKeeper oldKeeper = keeper;
            keeper = zooKeeper;
            try {
              closeKeeper(oldKeeper);
            } finally {
              if (isClosed) {
                // we may have been closed
                closeKeeper(SolrZkClient.this.keeper);
              }
            }
          });
    } catch (Exception e) {
      try (ParWork closer = new ParWork(this, true)) {
        zkConnManagerCallbackExecutor.shutdownNow();
        zkCallbackExecutor.shutdownNow();
        closer.collect(zkConnManagerCallbackExecutor);
        closer.collect(zkCallbackExecutor);
        closer.collect(connManager);
        closer.collect(keeper);
        closer.collect("zkClientCloseOnException");
      }
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }

    try {
      connManager.waitForConnected(clientConnectTimeout);
    } catch (Exception e) {
      ParWork.propegateInterrupt(e);
      try (ParWork closer = new ParWork(this, true)) {
        zkConnManagerCallbackExecutor.shutdownNow();
        zkCallbackExecutor.shutdownNow();
        closer.collect(zkConnManagerCallbackExecutor);
        closer.collect(zkCallbackExecutor);
        closer.collect(connManager);
        closer.collect(keeper);
        closer.collect("zkClientCloseOnException");
      }
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
    if (zkACLProvider == null) {
      this.zkACLProvider = createZkACLProvider();
    } else {
      this.zkACLProvider = zkACLProvider;
    }

    this.strat = strat;
    assert ObjectReleaseTracker.track(this);
  }

  public void setOnReconnect(OnReconnect onReconnect) {
    this.connManager.setOnReconnect(onReconnect);
  }

  public ConnectionManager getConnectionManager() {
    return connManager;
  }

  public ZkClientConnectionStrategy getZkClientConnectionStrategy() {
    return zkClientConnectionStrategy;
  }

  public ZkClientConnectionStrategy getStrat() {
    return strat;
  }

  public static final String ZK_CRED_PROVIDER_CLASS_NAME_VM_PARAM_NAME = "zkCredentialsProvider";
  protected ZkCredentialsProvider createZkCredentialsToAddAutomatically() {
    String zkCredentialsProviderClassName = System.getProperty(ZK_CRED_PROVIDER_CLASS_NAME_VM_PARAM_NAME);
    if (!StringUtils.isEmpty(zkCredentialsProviderClassName)) {
      try {
        log.info("Using ZkCredentialsProvider: {}", zkCredentialsProviderClassName);
        return (ZkCredentialsProvider)Class.forName(zkCredentialsProviderClassName).getConstructor().newInstance();
      } catch (Throwable t) {
        // just ignore - go default
        log.warn("VM param zkCredentialsProvider does not point to a class implementing ZkCredentialsProvider and with a non-arg constructor", t);
      }
    }
    log.debug("Using default ZkCredentialsProvider");
    return new DefaultZkCredentialsProvider();
  }

  public static final String ZK_ACL_PROVIDER_CLASS_NAME_VM_PARAM_NAME = "zkACLProvider";
  protected ZkACLProvider createZkACLProvider() {
    String zkACLProviderClassName = System.getProperty(ZK_ACL_PROVIDER_CLASS_NAME_VM_PARAM_NAME);
    if (!StringUtils.isEmpty(zkACLProviderClassName)) {
      try {
        log.info("Using ZkACLProvider: {}", zkACLProviderClassName);
        return (ZkACLProvider)Class.forName(zkACLProviderClassName).getConstructor().newInstance();
      } catch (Throwable t) {
        // just ignore - go default
        log.warn("VM param zkACLProvider does not point to a class implementing ZkACLProvider and with a non-arg constructor", t);
      }
    }
    log.debug("Using default ZkACLProvider");
    return new DefaultZkACLProvider();
  }

  /**
   * Returns true if client is connected
   */
  public boolean isConnected() {
    return keeper != null && keeper.getState() == ZooKeeper.States.CONNECTED;
  }

  public void delete(final String path, final int version, boolean retryOnConnLoss)
      throws InterruptedException, KeeperException {
    if (retryOnConnLoss) {
      zkCmdExecutor.retryOperation(() -> {
        keeper.delete(path, version);
        return null;
      });
    } else {
      keeper.delete(path, version);
    }
  }

  /**
   * Wraps the watcher so that it doesn't fire off ZK's event queue. In order to guarantee that a watch object will
   * only be triggered once for a given notification, users need to wrap their watcher using this method before
   * calling {@link #exists(String, org.apache.zookeeper.Watcher, boolean)} or
   * {@link #getData(String, org.apache.zookeeper.Watcher, org.apache.zookeeper.data.Stat, boolean)}.
   */
  public Watcher wrapWatcher(final Watcher watcher) {
    if (watcher == null || watcher instanceof ProcessWatchWithExecutor) return watcher;
    return new ProcessWatchWithExecutor(watcher);
  }

  /**
   * Return the stat of the node of the given path. Return null if no such a
   * node exists.
   * <p>
   * If the watch is non-null and the call is successful (no exception is thrown),
   * a watch will be left on the node with the given path. The watch will be
   * triggered by a successful operation that creates/delete the node or sets
   * the data on the node.
   *
   * @param path the node path
   * @param watcher explicit watcher
   * @return the stat of the node of the given path; return null if no such a
   *         node exists.
   * @throws KeeperException If the server signals an error
   * @throws InterruptedException If the server transaction is interrupted.
   * @throws IllegalArgumentException if an invalid path is specified
   */
  public Stat exists(final String path, final Watcher watcher, boolean retryOnConnLoss)
      throws KeeperException, InterruptedException {
    if (retryOnConnLoss) {
      return zkCmdExecutor.retryOperation(() -> keeper.exists(path, wrapWatcher(watcher)));
    } else {
      return keeper.exists(path, wrapWatcher(watcher));
    }
  }

  /**
   * Returns true if path exists
   */
  public Boolean exists(final String path, boolean retryOnConnLoss)
      throws KeeperException, InterruptedException {
    if (retryOnConnLoss) {
      return zkCmdExecutor.retryOperation(() -> keeper.exists(path, null) != null);
    } else {
      return keeper.exists(path, null) != null;
    }
  }

  /**
   * Returns children of the node at the path
   */
  public List<String> getChildren(final String path, final Watcher watcher, boolean retryOnConnLoss)
      throws KeeperException, InterruptedException {
    if (retryOnConnLoss) {
      return zkCmdExecutor.retryOperation(() -> keeper.getChildren(path, wrapWatcher(watcher)));
    } else {
      return keeper.getChildren(path, wrapWatcher(watcher));
    }
  }

  /**
   * Returns node's data
   */
  public byte[] getData(final String path, final Watcher watcher, final Stat stat, boolean retryOnConnLoss)
      throws KeeperException, InterruptedException {
    if (retryOnConnLoss) {
      return zkCmdExecutor.retryOperation(() -> keeper.getData(path, wrapWatcher(watcher), stat));
    } else {
      return keeper.getData(path, wrapWatcher(watcher), stat);
    }
  }

  /**
   * Returns node's state
   */
  public Stat setData(final String path, final byte data[], final int version, boolean retryOnConnLoss)
      throws KeeperException, InterruptedException {
    if (retryOnConnLoss) {
      return zkCmdExecutor.retryOperation(() -> keeper.setData(path, data, version));
    } else {
      return keeper.setData(path, data, version);
    }
  }

  public void atomicUpdate(String path, Function<byte[], byte[]> editor) throws KeeperException, InterruptedException {
   atomicUpdate(path, (stat, bytes) -> editor.apply(bytes));
  }

  public void atomicUpdate(String path, BiFunction<Stat , byte[], byte[]> editor) throws KeeperException, InterruptedException {
    for (; ; ) {
      byte[] modified = null;
      byte[] zkData = null;
      Stat s = new Stat();
      try {
        if (exists(path, true)) {
          zkData = getData(path, null, s, true);
          modified = editor.apply(s, zkData);
          if (modified == null) {
            //no change , no need to persist
            return;
          }
          setData(path, modified, s.getVersion(), true);
          break;
        } else {
          modified = editor.apply(s,null);
          if (modified == null) {
            //no change , no need to persist
            return;
          }
          create(path, modified, CreateMode.PERSISTENT, true);
          break;
        }
      } catch (KeeperException.BadVersionException | KeeperException.NodeExistsException e) {
        continue;
      }
    }


  }

  /**
   * Returns path of created node
   */
  public String create(final String path, final byte[] data,
      final CreateMode createMode, boolean retryOnConnLoss) throws KeeperException,
      InterruptedException {
    if (retryOnConnLoss) {
      return zkCmdExecutor.retryOperation(() -> keeper.create(path, data, zkACLProvider.getACLsToAdd(path),
          createMode));
    } else {
      List<ACL> acls = zkACLProvider.getACLsToAdd(path);
      return keeper.create(path, data, acls, createMode);
    }
  }

  /**
   * Creates the path in ZooKeeper, creating each node as necessary.
   *
   * e.g. If <code>path=/solr/group/node</code> and none of the nodes, solr,
   * group, node exist, each will be created.
   */
  public void makePath(String path, boolean retryOnConnLoss) throws KeeperException,
      InterruptedException {
    makePath(path, null, CreateMode.PERSISTENT, retryOnConnLoss);
  }

  public void makePath(String path, boolean failOnExists, boolean retryOnConnLoss) throws KeeperException,
      InterruptedException {
    makePath(path, null, CreateMode.PERSISTENT, null, failOnExists, retryOnConnLoss, 0);
  }

  public void makePath(String path, File file, boolean failOnExists, boolean retryOnConnLoss)
      throws IOException, KeeperException, InterruptedException {
    makePath(path, FileUtils.readFileToByteArray(file),
        CreateMode.PERSISTENT, null, failOnExists, retryOnConnLoss, 0);
  }

  public void makePath(String path, File file, boolean retryOnConnLoss) throws IOException,
      KeeperException, InterruptedException {
    makePath(path, FileUtils.readFileToByteArray(file), retryOnConnLoss);
  }

  public void makePath(String path, CreateMode createMode, boolean retryOnConnLoss) throws KeeperException,
      InterruptedException {
    makePath(path, null, createMode, retryOnConnLoss);
  }

  /**
   * Creates the path in ZooKeeper, creating each node as necessary.
   *
   * @param data to set on the last zkNode
   */
  public void makePath(String path, byte[] data, boolean retryOnConnLoss) throws KeeperException,
      InterruptedException {
    makePath(path, data, CreateMode.PERSISTENT, retryOnConnLoss);
  }

  /**
   * Creates the path in ZooKeeper, creating each node as necessary.
   *
   * e.g. If <code>path=/solr/group/node</code> and none of the nodes, solr,
   * group, node exist, each will be created.
   *
   * @param data to set on the last zkNode
   */
  public void makePath(String path, byte[] data, CreateMode createMode, boolean retryOnConnLoss)
      throws KeeperException, InterruptedException {
    makePath(path, data, createMode, null, retryOnConnLoss);
  }

  /**
   * Creates the path in ZooKeeper, creating each node as necessary.
   *
   * e.g. If <code>path=/solr/group/node</code> and none of the nodes, solr,
   * group, node exist, each will be created.
   *
   * @param data to set on the last zkNode
   */
  public void makePath(String path, byte[] data, CreateMode createMode,
      Watcher watcher, boolean retryOnConnLoss) throws KeeperException, InterruptedException {
    makePath(path, data, createMode, watcher, true, retryOnConnLoss, 0);
  }

  /**
   * Creates the path in ZooKeeper, creating each node as necessary.
   *
   * e.g. If <code>path=/solr/group/node</code> and none of the nodes, solr,
   * group, node exist, each will be created.
   *
   * @param data to set on the last zkNode
   */
  public void makePath(String path, byte[] data, CreateMode createMode,
      Watcher watcher, boolean failOnExists, boolean retryOnConnLoss) throws KeeperException, InterruptedException {
    makePath(path, data, createMode, watcher, failOnExists, retryOnConnLoss, 0);
  }

  /**
   * Creates the path in ZooKeeper, creating each node as necessary.
   *
   * e.g. If <code>path=/solr/group/node</code> and none of the nodes, solr,
   * group, node exist, each will be created.
   *
   * skipPathParts will force the call to fail if the first skipPathParts do not exist already.
   *
   * Note: retryOnConnLoss is only respected for the final node - nodes
   * before that are always retried on connection loss.
   */
  public void makePath(String path, byte[] data, CreateMode createMode,
      Watcher watcher, boolean failOnExists, boolean retryOnConnLoss, int skipPathParts) throws KeeperException, InterruptedException {
    if (path.endsWith("autoscaling.json")) {
      throw new IllegalArgumentException();
    }
    log.debug("makePath: {}", path);
    boolean retry = true;

    if (path.startsWith("/")) {
      path = path.substring(1, path.length());
    }
    String[] paths = path.split("/");
    StringBuilder sbPath = new StringBuilder();
    for (int i = 0; i < paths.length; i++) {
      String pathPiece = paths[i];
      sbPath.append("/").append(pathPiece);
      if (i < skipPathParts) {
        continue;
      }
      byte[] bytes = null;
      final String currentPath = sbPath.toString();

      CreateMode mode = CreateMode.PERSISTENT;
      if (i == paths.length - 1) {
        mode = createMode;
        bytes = data;
        if (!retryOnConnLoss) retry = false;
      }
      try {
        if (retry) {
          final CreateMode finalMode = mode;
          final byte[] finalBytes = bytes;
          zkCmdExecutor.retryOperation(() -> {
            keeper.create(currentPath, finalBytes, zkACLProvider.getACLsToAdd(currentPath), finalMode);
            return null;
          });
        } else {
          keeper.create(currentPath, bytes, zkACLProvider.getACLsToAdd(currentPath), mode);
        }
      } catch (NoAuthException e) {
        // in auth cases, we may not have permission for an earlier part of a path, which is fine
        if (i == paths.length - 1 || !exists(currentPath, retryOnConnLoss)) {

          throw e;
        }
      } catch (NodeExistsException e) {

        if (!failOnExists && i == paths.length - 1) {
          // TODO: version ? for now, don't worry about race
          setData(currentPath, data, -1, retryOnConnLoss);
          // set new watch
          exists(currentPath, watcher, retryOnConnLoss);
          return;
        }

        // ignore unless it's the last node in the path
        if (i == paths.length - 1) {
          throw e;
        }
      }

    }
  }

  public void makePath(String zkPath, CreateMode createMode, Watcher watcher, boolean retryOnConnLoss)
      throws KeeperException, InterruptedException {
    makePath(zkPath, null, createMode, watcher, retryOnConnLoss);
  }

  public void mkDirs(String path, byte[] bytes) throws KeeperException {
    Map<String,byte[]> dataMap = new HashMap<String,byte[]>(1);
    dataMap.put(path, bytes);
    mkDirs(dataMap);
  }

  public void mkDirs(String... paths) throws KeeperException {
    Map<String,byte[]> dataMap = new HashMap<String,byte[]>(paths.length);
    for (String path : paths) {
      dataMap.put(path, null);
    }
    mkDirs(dataMap);
  }

  public void mkDirs(Map<String,byte[]> dataMap) throws KeeperException {
    mkDirs(dataMap, Collections.emptyMap());
  }

  public void mkDirs(Map<String,byte[]> dataMap, Map<String,CreateMode> createModeMap) throws KeeperException {
    Set<String> paths = dataMap.keySet();

    if (log.isDebugEnabled()) {
      log.debug("mkDirs(String paths={}) - start", paths);
    }
    Set<String> madePaths = new HashSet<>(paths.size() * 3);
    List<String> pathsToMake = new ArrayList<>(paths.size() * 3);

    for (String fullpath : paths) {
      if (!fullpath.startsWith("/")) throw new IllegalArgumentException("Paths must start with /, " + fullpath);
      StringBuilder sb = new StringBuilder();
      if (log.isDebugEnabled()) {
        log.debug("path {}", fullpath);
      }
      String[] subpaths = fullpath.split("/");
      for (String subpath : subpaths) {
        if (subpath.length() == 0) continue;
        if (log.isDebugEnabled()) {
          log.debug("subpath {}", subpath);
        }
        sb.append("/" + subpath.replaceAll("\\/", ""));
        pathsToMake.add(sb.toString());
      }
    }

    CountDownLatch latch = new CountDownLatch(pathsToMake.size());
    int[] code = new int[1];
    String[] path = new String[1];
    boolean[]  failed = new boolean[1];
    boolean[] nodata = new boolean[1];
    for (String makePath : pathsToMake) {
      path[0] = null;
      nodata[0] = false;
      code[0] = 0;
      if (!makePath.startsWith("/")) makePath = "/" + makePath;

      byte[] data = dataMap.get(makePath);

      CreateMode createMode = createModeMap.get(makePath);

      if (createMode == null) {
        createMode = CreateMode.PERSISTENT;
      }

      if (!madePaths.add(makePath)) {
        if (log.isDebugEnabled()) log.debug("skipping already made {}", makePath + " data: " + (data == null ? "none" : data.length + "b"));
        // already made
        latch.countDown();
        continue;
      }
      if (log.isDebugEnabled()) log.debug("makepath {}", makePath + " data: " + (data == null ? "none" : data.length + "b"));

      assert getZkACLProvider() != null;
      assert keeper != null;
      keeper.create(makePath, data, getZkACLProvider().getACLsToAdd(makePath), createMode,
              (resultCode, zkpath, context, name) -> {
                code[0] = resultCode;
                if (resultCode != 0) {
                  failed[0] = true;
                  path[0] = "" + zkpath;
                  nodata[0] = data == null;
                }

                latch.countDown();
              }, "");



    }


    boolean success = false;
    try {
      success = latch.await(15, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      log.error("mkDirs(String=" + paths + ")", e);

      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }

    // nocommit, still haackey, do fails right
    if (code[0] != 0) {
      KeeperException e = KeeperException.create(KeeperException.Code.get(code[0]), path[0]);
      if (e instanceof NodeExistsException && (nodata[0])) {
        // okay
      } else {
        throw e;
      }
    }

    if (!success) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Timeout waiting for operatoins to complete");
    }

    if (log.isDebugEnabled()) {
      log.debug("mkDirs(String) - end");
    }
  }

  public void mkdirs(String znode, File file) {
    try {
      mkDirs(znode, FileUtils.readFileToByteArray(file));
    } catch (Exception e) {
      ParWork.propegateInterrupt(e);
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
  }

  /**
   * Write data to ZooKeeper.
   */
  public Stat setData(String path, byte[] data, boolean retryOnConnLoss) throws KeeperException,
      InterruptedException {
    return setData(path, data, -1, retryOnConnLoss);
  }

  /**
   * Write file to ZooKeeper - default system encoding used.
   *
   * @param path path to upload file to e.g. /solr/conf/solrconfig.xml
   * @param file path to file to be uploaded
   */
  public Stat setData(String path, File file, boolean retryOnConnLoss) throws IOException,
      KeeperException, InterruptedException {
    if (log.isDebugEnabled()) {
      log.debug("Write to ZooKeeper: {} to {}", file.getAbsolutePath(), path);
    }
    byte[] data = FileUtils.readFileToByteArray(file);
    return setData(path, data, retryOnConnLoss);
  }

  public List<OpResult> multi(final Iterable<Op> ops, boolean retryOnConnLoss) throws InterruptedException, KeeperException {
    List<String> errors = new ArrayList<>();
    List<OpResult> results;

    if (retryOnConnLoss) {
      results = zkCmdExecutor.retryOperation(() -> keeper.multi(ops));
    } else {
      results = keeper.multi(ops);
    }

    Iterator<Op> it = ops.iterator();
    for (OpResult result : results) {
      Op reqOp = it.next();
      if (result instanceof OpResult.ErrorResult) {
        OpResult.ErrorResult dresult = (OpResult.ErrorResult) result;
        if (dresult.getErr() != 0) {
          errors.add("path=" + reqOp.getPath() + " err=" + dresult.getErr());
        }
      }
    }
    if (errors.size() > 0) {
      log.error("Errors", errors.toString());
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, errors.toString());
    }
    return results;
  }

  /**
   * Fills string with printout of current ZooKeeper layout.
   */
  public void printLayout(String path, int indent, StringBuilder string) {

    byte[] data = null;
    Stat stat = new Stat();
    List<String> children = Collections.emptyList();
    try {
      data = getData(path, null, stat, true);

      children = getChildren(path, null, true);
      Collections.sort(children);
    } catch (InterruptedException e1) {
      checkInterrupted(e1);
      // continue
    } catch (KeeperException e1) {
      checkInterrupted(e1);
      if (e1 instanceof KeeperException.NoNodeException) {
        // things change ...
        return;
      }
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e1);
    }
    StringBuilder dent = new StringBuilder();
    for (int i = 0; i < indent; i++) {
      dent.append(" ");
    }
    string.append(dent).append(path).append(" (c=").append(children.size()).append(",v=" + (stat == null ? "?" : stat.getVersion()) + ")").append(NEWL);
    if (data != null) {
      String dataString = new String(data, StandardCharsets.UTF_8);
      if ((stat != null && stat.getDataLength() < MAX_BYTES_FOR_ZK_LAYOUT_DATA_SHOW && dataString.split("\\r\\n|\\r|\\n").length < 6) || path.endsWith("state.json")) {
        if (path.endsWith(".xml")) {
          // this is the cluster state in xml format - lets pretty print
          dataString = prettyPrint(path, dataString);
        }

        string.append(dent).append("DATA (" + (stat != null ? stat.getDataLength() : "?") + "b) :\n").append(dent).append("    ")
                .append(dataString.replaceAll("\n", "\n" + dent + "    ")).append(NEWL);
      } else {
        string.append(dent).append("DATA (" + (stat != null ? stat.getDataLength() : "?") + "b) : ...supressed...").append(NEWL);
      }
    }
    indent += 1;
    for (String child : children) {
      if (!child.equals("quota")) {
        printLayout(path + (path.equals("/") ? "" : "/") + child, indent,
                string);
      }
    }
  }

  public void printLayout() {
    StringBuilder sb = new StringBuilder();
    printLayout("/", 0, sb);
    log.warn("\n\n_____________________________________________________________________\n\n\nZOOKEEPER LAYOUT:\n\n" + sb.toString() + "\n\n_____________________________________________________________________\n\n");
  }

  public void printLayoutToStream(PrintStream out) {
    StringBuilder sb = new StringBuilder();
    printLayout("/", 0, sb);
    out.println(sb.toString());
  }

  public void printLayoutToFile(Path file) {
    StringBuilder sb = new StringBuilder();
    printLayout("/", 0, sb);
    try {
      Files.writeString(file, sb.toString(), StandardOpenOption.CREATE);
    } catch (IOException e) {
      throw new RuntimeIOException(e);
    }
  }

  public static String prettyPrint(String path, String dataString, int indent) {
    try {
      Source xmlInput = new StreamSource(new StringReader(dataString));
      try (StringWriter stringWriter = new StringWriter()) {
        StreamResult xmlOutput = new StreamResult(stringWriter);
        try (Writer writer = xmlOutput.getWriter()) {
          return writer.toString();
        }
      } finally {
        IOUtils.closeQuietly(((StreamSource) xmlInput).getInputStream());
      }
    } catch (Exception e) {
      log.error("prettyPrint(path={}, dataString={})", dataString, indent, e);

      checkInterrupted(e);
      return "XML Parsing Failure";
    }
  }

  private static String prettyPrint(String path, String input) {
    String returnString = prettyPrint(path, input, 2);
    return returnString;
  }

  public void close() {
    if (isClosed) return; // it's okay if we over close - same as solrcore
    isClosed = true;
    zkConnManagerCallbackExecutor.shutdownNow();
    zkCallbackExecutor.shutdownNow();
    try (ParWork worker = new ParWork(this, true)) {

      worker.add("ZkClientExecutors&ConnMgr", zkCallbackExecutor, zkConnManagerCallbackExecutor, connManager, keeper);
    }


    assert ObjectReleaseTracker.release(this);
  }

  public boolean isClosed() {
    return isClosed || (higherLevelIsClosed != null && higherLevelIsClosed.isClosed());
  }

  /**
   * Allows package private classes to update volatile ZooKeeper.
   */
  void updateKeeper(SolrZooKeeper keeper) throws InterruptedException {
   SolrZooKeeper oldKeeper = this.keeper;
   this.keeper = keeper;
   if (oldKeeper != null) {
     oldKeeper.close();
   }
   // we might have been closed already
   if (isClosed) this.keeper.close();
  }

  public SolrZooKeeper getSolrZooKeeper() {
    return keeper;
  }

  private void closeKeeper(SolrZooKeeper keeper) {
    if (keeper != null) {
      keeper.close();
    }
  }

  /**
   * Validates if zkHost contains a chroot. See http://zookeeper.apache.org/doc/r3.2.2/zookeeperProgrammers.html#ch_zkSessions
   */
  public static boolean containsChroot(String zkHost) {
    return zkHost.contains("/");
  }

  /**
   * Check to see if a Throwable is an InterruptedException, and if it is, set the thread interrupt flag
   * @param e the Throwable
   * @return the Throwable
   */
  public static Throwable checkInterrupted(Throwable e) {
    if (e instanceof InterruptedException)
      Thread.currentThread().interrupt();
    return e;
  }

  /**
   * @return the address of the zookeeper cluster
   */
  public String getZkServerAddress() {
    return zkServerAddress;
  }

  /**
   * Gets the raw config node /zookeeper/config as returned by server. Response may look like
   * <pre>
   * server.1=localhost:2780:2783:participant;localhost:2791
   * server.2=localhost:2781:2784:participant;localhost:2792
   * server.3=localhost:2782:2785:participant;localhost:2793
   * version=400000003
   * </pre>
   * @return Multi line string representing the config. For standalone ZK this will return empty string
   */
  public String getConfig() {
    try {
      Stat stat = new Stat();
      keeper.sync(ZooDefs.CONFIG_NODE, null, null);
      byte[] data = keeper.getConfig(false, stat);
      if (data == null || data.length == 0) {
        return "";
      }
      return new String(data, StandardCharsets.UTF_8);
    } catch (KeeperException|InterruptedException ex) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Failed to get config from zookeeper", ex);
    }
  }

  public ZkACLProvider getZkACLProvider() {
    return zkACLProvider;
  }

  /**
   * Set the ACL on a single node in ZooKeeper. This will replace all existing ACL on that node.
   *
   * @param path path to set ACL on e.g. /solr/conf/solrconfig.xml
   * @param acls a list of {@link ACL}s to be applied
   * @param retryOnConnLoss true if the command should be retried on connection loss
   */
  public Stat setACL(String path, List<ACL> acls, boolean retryOnConnLoss) throws InterruptedException, KeeperException  {
    if (retryOnConnLoss) {
      return zkCmdExecutor.retryOperation(() -> keeper.setACL(path, acls, -1));
    } else {
      return keeper.setACL(path, acls, -1);
    }
  }

  public void setHigherLevelIsClosed(IsClosed isClosed) {
    this.higherLevelIsClosed = isClosed;
  }

  /**
   * Update all ACLs for a zk tree based on our configured {@link ZkACLProvider}.
   * @param root the root node to recursively update
   */
  public void updateACLs(final String root) throws KeeperException, InterruptedException {
    ZkMaintenanceUtils.traverseZkTree(this, root, ZkMaintenanceUtils.VISIT_ORDER.VISIT_POST, path -> {
      try {
        setACL(path, getZkACLProvider().getACLsToAdd(path), true);
        log.debug("Updated ACL on {}", path);
      } catch (NoNodeException e) {
        // If a node was deleted, don't bother trying to set ACLs on it.
        return;
      }
    });
  }

  // Some pass-throughs to allow less code disruption to other classes that use SolrZkClient.
  public void clean(String path) throws InterruptedException, KeeperException {
    ZkMaintenanceUtils.clean(this, path);
  }

  public void clean(String path, Predicate<String> nodeFilter) throws InterruptedException, KeeperException {
    log.info("clean path {}" + path);
    ZkMaintenanceUtils.clean(this, path, nodeFilter);
  }

  public void upConfig(Path confPath, String confName) throws IOException, KeeperException {
    ZkMaintenanceUtils.upConfig(this, confPath, confName);
  }

  public String listZnode(String path, Boolean recurse) throws KeeperException, InterruptedException, SolrServerException {
    return ZkMaintenanceUtils.listZnode(this, path, recurse);
  }

  public void downConfig(String confName, Path confPath) throws IOException {
    ZkMaintenanceUtils.downConfig(this, confName, confPath);
  }

  public void zkTransfer(String src, Boolean srcIsZk,
                         String dst, Boolean dstIsZk,
                         Boolean recurse) throws SolrServerException, KeeperException, InterruptedException, IOException {
    ZkMaintenanceUtils.zkTransfer(this, src, srcIsZk, dst, dstIsZk, recurse);
  }

  public void moveZnode(String src, String dst) throws SolrServerException, KeeperException, InterruptedException {
    ZkMaintenanceUtils.moveZnode(this, src, dst);
  }

  public void uploadToZK(final Path rootPath, final String zkPath,
                         final Pattern filenameExclusions) throws IOException, KeeperException {
    ZkMaintenanceUtils.uploadToZK(this, rootPath, zkPath, filenameExclusions);
  }
  public void downloadFromZK(String zkPath, Path dir) throws IOException {
    ZkMaintenanceUtils.downloadFromZK(this, zkPath, dir);
  }

  public Op createPathOp(String path) {
    return createPathOp(path, null);
  }

  public Op createPathOp(String path, byte[] data) {
    return Op.create(path, data, getZkACLProvider().getACLsToAdd(path), CreateMode.PERSISTENT);
  }

  public void setAclProvider(ZkACLProvider zkACLProvider) {
    this.zkACLProvider = zkACLProvider;
  }

  public void setIsClosed(IsClosed isClosed) {
    this.higherLevelIsClosed = isClosed;
  }

  public void setDisconnectListener(ConnectionManager.DisconnectListener dl) {
    this.connManager.setDisconnectListener(dl);

  }

  /**
   * Watcher wrapper that ensures that heavy implementations of process do not interfere with our ability
   * to react to other watches, but also ensures that two wrappers containing equal watches are considered
   * equal (and thus we won't accumulate multiple wrappers of the same watch).
   */
  private final class ProcessWatchWithExecutor implements Watcher { // see below for why final.
    private final Watcher watcher;

    ProcessWatchWithExecutor(Watcher watcher) {
      if (watcher == null) {
        throw new IllegalArgumentException("Watcher must not be null");
      }
      this.watcher = watcher;
    }

    @Override
    public void process(final WatchedEvent event) {
      if (isClosed) {
        return;
      }
      if (log.isDebugEnabled()) log.debug("Submitting job to respond to event {}", event);
      try {
        if (watcher instanceof ConnectionManager) {
          zkConnManagerCallbackExecutor.submit(() -> watcher.process(event));
        } else {
          zkCallbackExecutor.submit(() -> watcher.process(event));
        }
      } catch (RejectedExecutionException e) {
        log.info("Rejected from executor", e);
      }
    }

    // These overrides of hashcode/equals ensure that we don't store the same exact watch
    // multiple times in org.apache.zookeeper.ZooKeeper.ZKWatchManager.dataWatches
    // (a Map<String<Set<Watch>>). This class is marked final to avoid oddball
    // cases with sub-classes, if you need different behavior, find a new class or make
    // sure you account for the case where two diff sub-classes with different behavior
    // for process(WatchEvent) and have been created with the same watch object.
    @Override
    public int hashCode() {
      return watcher.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof ProcessWatchWithExecutor) {
        return this.watcher.equals(((ProcessWatchWithExecutor) obj).watcher);
      }
      return false;
    }
  }
}
