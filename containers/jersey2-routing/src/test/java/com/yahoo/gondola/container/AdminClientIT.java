/*
 * Copyright 2015, Yahoo Inc.
 * Copyrights licensed under the New BSD License.
 * See the accompanying LICENSE file for terms.
 */

package com.yahoo.gondola.container;

import com.yahoo.gondola.Config;
import com.yahoo.gondola.Gondola;
import com.yahoo.gondola.RoleChangeEvent;
import com.yahoo.gondola.Shard;
import com.yahoo.gondola.container.client.ShardManagerClient;
import com.yahoo.gondola.container.client.ZookeeperShardManagerClient;
import com.yahoo.gondola.container.impl.DirectShardManagerClient;
import com.yahoo.gondola.container.impl.ZookeeperShardManagerServer;
import com.yahoo.gondola.container.utils.ZookeeperServer;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.internal.util.reflection.Whitebox;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.yahoo.gondola.container.AdminClientIT.Type.DIRECT;
import static com.yahoo.gondola.container.AdminClientIT.Type.ZOOKEEPER;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

public class AdminClientIT {

    public static final String SERVICE_NAME = "foo";
    public static final String CLIENT_NAME = "admin";
    URL configFileURL = AdminClientIT.class.getClassLoader().getResource("gondola.conf");
    Config config = new Config(new File(configFileURL.getFile()));
    ZookeeperServer zookeeperServer = new ZookeeperServer();

    // shardId -> hostId
    Map<String, String> routingTable;

    // hostId -> LocalTestRoutingServer
    Map<String, LocalTestRoutingServer> addressTable;
    Map<String, CountDownLatch> latches;
    ShardManagerClient shardManagerClient;
    List<Gondola> gondolas;
    AdminClient adminClient;
    List<ShardManagerServer> shardManagerServers;

    @Mock
    ConfigWriter configWriter;

    enum Type {DIRECT, ZOOKEEPER}

    public void setUp(Type type) throws Exception {
        MockitoAnnotations.initMocks(this);
        when(routingServiceMap.get(any())).thenReturn(routingService);
        when(routingService.provideChangeLogConsumer()).thenReturn((shardId1, command) -> {});
        routingTable = new ConcurrentHashMap<>();
        addressTable = new HashMap<>();
        latches = new HashMap<>();
        shardManagerClient = getShardManagerClient(type, CLIENT_NAME);
        config.getShardIds().forEach(shardId -> latches.put(shardId, new CountDownLatch(1)));
        gondolas = new ArrayList<>();
        shardManagerServers = new ArrayList<>();
        for (String hostId : config.getHostIds()) {
            Gondola gondola = new Gondola(config, hostId);
            gondola.registerForRoleChanges(getRoleChangeEventListener());
            gondola.start();
            gondolas.add(gondola);
            LocalTestRoutingServer testServer = getLocalTestRoutingServer(gondola);
            ShardManager
                shardManager =
                new ShardManager(gondola, testServer.routingFilter, config, getShardManagerClient(type, hostId));
            getShardManagerServer(type, gondola, shardManager);
            addressTable.put(hostId, testServer);
        }
        for (Map.Entry<String, CountDownLatch> e : latches.entrySet()) {
            e.getValue().await();
        }
        when(configWriter.save()).thenReturn(config.getFile());
        adminClient = new AdminClient(SERVICE_NAME, shardManagerClient, config, configWriter, new GondolaAdminClient(config));
    }

    private Consumer<RoleChangeEvent> getRoleChangeEventListener() {
        return roleChangeEvent -> {
            if (roleChangeEvent.leader != null) {
                latches.get(roleChangeEvent.shard.getShardId()).countDown();
                routingTable.put(roleChangeEvent.shard.getShardId(),
                                 config.getMember(roleChangeEvent.leader.getMemberId()).getHostId());
            }
        };
    }

    @Mock
    RoutingService routingService;

    @Mock
    Map<String, RoutingService> routingServiceMap;

    private LocalTestRoutingServer getLocalTestRoutingServer(final Gondola gondola) throws Exception {
        return new LocalTestRoutingServer(gondola, request -> 1, new ProxyClientProvider(),
                                          new HashMap<String, RoutingService>() {
                                              {
                                                  for (Shard shard : gondola.getShardsOnHost()) {
                                                      put(shard.getShardId(), routingService);
                                                  }
                                              }
                                          }, new ChangeLogProcessor(gondola, routingServiceMap));
    }

    private ShardManagerServer getShardManagerServer(Type type, Gondola gondola,
                                                     ShardManager shardManager) {
        switch (type) {
            case DIRECT:
                for (Config.ConfigMember m : config.getMembersInHost(gondola.getHostId())) {
                    ((DirectShardManagerClient) shardManagerClient)
                        .setShardManager(m.getMemberId(), shardManager);
                }
            case ZOOKEEPER:
                ZookeeperShardManagerServer shardManagerServer =
                    new ZookeeperShardManagerServer(SERVICE_NAME, zookeeperServer.getConnectString(), gondola, shardManager);
                shardManagerServers.add(shardManagerServer);
                return shardManagerServer;
        }
        return null;
    }

    private ShardManagerClient getShardManagerClient(Type type, String clientName) {
        ShardManagerClient shardManagerClient = null;
        switch (type) {
            case DIRECT:
                if (this.shardManagerClient == null) {
                    this.shardManagerClient = new DirectShardManagerClient(config);
                }
                shardManagerClient = this.shardManagerClient;
                break;
            case ZOOKEEPER:
                shardManagerClient =
                    new ZookeeperShardManagerClient(SERVICE_NAME, clientName, zookeeperServer.getConnectString(),
                                                    config);
                break;
        }
        return shardManagerClient;
    }

    @DataProvider(name = "typeProvider")
    public Object[][] typeProvider() {
        return new Object[][]{
            {ZOOKEEPER},
            {DIRECT}
        };
    }

    public void tearDown() throws Exception {
        gondolas.parallelStream().forEach(Gondola::stop);
        zookeeperServer.reset();
        shardManagerServers.forEach(ShardManagerServer::stop);
        shardManagerClient = null;
    }

    @Test(invocationCount = 1)
    public void testAssignBuckets_direct() throws Exception {
        testAssignBucketByType(DIRECT);
    }

    @Test(invocationCount = 1)
    public void testAssignBuckets_zookeeper() throws Exception {
        testAssignBucketByType(ZOOKEEPER);
    }


    private void testAssignBucketByType(Type type) throws Exception {
        setUp(type);
        for (BucketManager bucketManager : getBucketManagersFromAllHosts()) {
            assertEquals(bucketManager.lookupBucketTable(0).shardId, "shard1");
            assertEquals(bucketManager.lookupBucketTable(0).migratingShardId, null);
        }

        try {
            adminClient.assignBuckets(0, 10, "shard1", "shard2");
            for (BucketManager bucketManager : getBucketManagersFromAllHosts()) {
                assertEquals(bucketManager.lookupBucketTable(0).shardId, "shard2");
                assertEquals(bucketManager.lookupBucketTable(0).migratingShardId, null);
            }
            /* TODO: add this back when support migration back
            adminClient.assignBuckets(0, 10, "shard2", "shard1");
            for (BucketManager bucketManager : getBucketManagersFromAllHosts()) {
                assertEquals(bucketManager.lookupBucketTable(0).shardId, "shard1");
                assertEquals(bucketManager.lookupBucketTable(0).migratingShardId, null);
            }
            */
        } finally {
            tearDown();
        }
    }

    private List<BucketManager> getBucketManagersFromAllHosts() {
        return addressTable.entrySet().stream().map(e -> getBucketManager(e.getValue().routingFilter))
            .collect(Collectors.toList());
    }

    private BucketManager getBucketManager(RoutingFilter filter) {
        return (BucketManager) Whitebox.getInternalState(filter, "bucketManager");
    }
}