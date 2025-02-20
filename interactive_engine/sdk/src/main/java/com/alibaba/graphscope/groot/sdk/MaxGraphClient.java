/**
 * Copyright 2020 Alibaba Group Holding Limited.
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.graphscope.groot.sdk;

import com.alibaba.graphscope.proto.write.BatchWriteRequest;
import com.alibaba.graphscope.proto.write.BatchWriteResponse;
import com.alibaba.graphscope.proto.write.ClientWriteGrpc;
import com.alibaba.graphscope.proto.write.ClientWriteGrpc.ClientWriteBlockingStub;
import com.alibaba.graphscope.proto.write.DataRecordPb;
import com.alibaba.graphscope.proto.write.GetClientIdRequest;
import com.alibaba.graphscope.proto.write.WriteRequestPb;
import com.alibaba.graphscope.proto.write.WriteTypePb;
import com.alibaba.maxgraph.compiler.api.schema.GraphSchema;
import com.alibaba.maxgraph.proto.groot.ClearIngestRequest;
import com.alibaba.maxgraph.proto.groot.ClientBackupGrpc;
import com.alibaba.maxgraph.proto.groot.ClientBackupGrpc.ClientBackupBlockingStub;
import com.alibaba.maxgraph.proto.groot.ClientGrpc;
import com.alibaba.maxgraph.proto.groot.ClientGrpc.ClientBlockingStub;
import com.alibaba.maxgraph.proto.groot.CommitDataLoadRequest;
import com.alibaba.maxgraph.proto.groot.CommitDataLoadResponse;
import com.alibaba.maxgraph.proto.groot.CreateNewGraphBackupRequest;
import com.alibaba.maxgraph.proto.groot.CreateNewGraphBackupResponse;
import com.alibaba.maxgraph.proto.groot.DeleteGraphBackupRequest;
import com.alibaba.maxgraph.proto.groot.DropSchemaRequest;
import com.alibaba.maxgraph.proto.groot.DropSchemaResponse;
import com.alibaba.maxgraph.proto.groot.GetGraphBackupInfoRequest;
import com.alibaba.maxgraph.proto.groot.GetGraphBackupInfoResponse;
import com.alibaba.maxgraph.proto.groot.GetMetricsRequest;
import com.alibaba.maxgraph.proto.groot.GetMetricsResponse;
import com.alibaba.maxgraph.proto.groot.GetPartitionNumRequest;
import com.alibaba.maxgraph.proto.groot.GetPartitionNumResponse;
import com.alibaba.maxgraph.proto.groot.GetSchemaRequest;
import com.alibaba.maxgraph.proto.groot.GetSchemaResponse;
import com.alibaba.maxgraph.proto.groot.IngestDataRequest;
import com.alibaba.maxgraph.proto.groot.LoadJsonSchemaRequest;
import com.alibaba.maxgraph.proto.groot.LoadJsonSchemaResponse;
import com.alibaba.maxgraph.proto.groot.PrepareDataLoadRequest;
import com.alibaba.maxgraph.proto.groot.PrepareDataLoadResponse;
import com.alibaba.maxgraph.proto.groot.PurgeOldGraphBackupsRequest;
import com.alibaba.maxgraph.proto.groot.RemoteFlushRequest;
import com.alibaba.maxgraph.proto.groot.RestoreFromGraphBackupRequest;
import com.alibaba.maxgraph.proto.groot.VerifyGraphBackupRequest;
import com.alibaba.maxgraph.proto.groot.VerifyGraphBackupResponse;
import com.alibaba.maxgraph.sdkcommon.BasicAuth;
import com.alibaba.maxgraph.sdkcommon.common.BackupInfo;
import com.alibaba.maxgraph.sdkcommon.common.DataLoadTarget;
import com.alibaba.maxgraph.sdkcommon.common.EdgeRecordKey;
import com.alibaba.maxgraph.sdkcommon.common.VertexRecordKey;
import com.alibaba.maxgraph.sdkcommon.schema.GraphDef;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import org.apache.tinkerpop.gremlin.driver.Client;
import org.apache.tinkerpop.gremlin.driver.CloseableGremlinClient;
import org.apache.tinkerpop.gremlin.driver.ResultSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class MaxGraphClient implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(MaxGraphClient.class);

    private ClientGrpc.ClientBlockingStub stub;
    private ClientWriteGrpc.ClientWriteBlockingStub writeStub;
    private ClientBackupGrpc.ClientBackupBlockingStub backupStub;
    private CloseableGremlinClient gremlinClient;
    private ManagedChannel channel;
    private String clientId = "DEFAULT";

    private BatchWriteRequest.Builder batchWriteBuilder;

    private MaxGraphClient(
            ClientBlockingStub clientBlockingStub,
            ClientWriteBlockingStub clientWriteBlockingStub,
            ClientBackupBlockingStub clientBackupBlockingStub,
            CloseableGremlinClient gremlinClient) {
        this.stub = clientBlockingStub;
        this.writeStub = clientWriteBlockingStub;
        this.backupStub = clientBackupBlockingStub;
        this.gremlinClient = gremlinClient;
        this.reset();
    }

    private void reset() {
        this.batchWriteBuilder = BatchWriteRequest.newBuilder().setClientId(this.clientId);
    }

    public void initWriteSession() {
        this.clientId =
                this.writeStub.getClientId(GetClientIdRequest.newBuilder().build()).getClientId();
        this.reset();
    }

    public void addVertex(String label, Map<String, String> properties) {
        VertexRecordKey vertexRecordKey = new VertexRecordKey(label);
        WriteRequestPb writeRequest =
                WriteRequestPb.newBuilder()
                        .setWriteType(WriteTypePb.INSERT)
                        .setDataRecord(
                                DataRecordPb.newBuilder()
                                        .setVertexRecordKey(vertexRecordKey.toProto())
                                        .putAllProperties(properties)
                                        .build())
                        .build();
        this.batchWriteBuilder.addWriteRequests(writeRequest);
    }

    public void addEdge(
            String label,
            String srcLabel,
            String dstLabel,
            Map<String, String> srcPk,
            Map<String, String> dstPk,
            Map<String, String> properties) {
        VertexRecordKey srcVertexKey =
                new VertexRecordKey(srcLabel, Collections.unmodifiableMap(srcPk));
        VertexRecordKey dstVertexKey =
                new VertexRecordKey(dstLabel, Collections.unmodifiableMap(dstPk));
        EdgeRecordKey edgeRecordKey = new EdgeRecordKey(label, srcVertexKey, dstVertexKey);
        WriteRequestPb writeRequest =
                WriteRequestPb.newBuilder()
                        .setWriteType(WriteTypePb.INSERT)
                        .setDataRecord(
                                DataRecordPb.newBuilder()
                                        .setEdgeRecordKey(edgeRecordKey.toProto())
                                        .putAllProperties(properties)
                                        .build())
                        .build();
        this.batchWriteBuilder.addWriteRequests(writeRequest);
    }

    public long commit() {
        long snapshotId = 0L;
        if (this.batchWriteBuilder.getWriteRequestsCount() > 0) {
            BatchWriteResponse response = this.writeStub.batchWrite(this.batchWriteBuilder.build());
            snapshotId = response.getSnapshotId();
        }
        this.reset();
        return snapshotId;
    }

    public void remoteFlush(long snapshotId) {
        this.stub.remoteFlush(RemoteFlushRequest.newBuilder().setSnapshotId(snapshotId).build());
    }

    public GraphSchema getSchema() {
        GetSchemaResponse response = this.stub.getSchema(GetSchemaRequest.newBuilder().build());
        return GraphDef.parseProto(response.getGraphDef());
    }

    public GraphSchema dropSchema() {
        DropSchemaResponse response = this.stub.dropSchema(DropSchemaRequest.newBuilder().build());
        return GraphDef.parseProto(response.getGraphDef());
    }

    public GraphSchema prepareDataLoad(List<DataLoadTarget> targets) {
        PrepareDataLoadRequest.Builder builder = PrepareDataLoadRequest.newBuilder();
        for (DataLoadTarget target : targets) {
            builder.addDataLoadTargets(target.toProto());
        }
        PrepareDataLoadResponse response = this.stub.prepareDataLoad(builder.build());
        return GraphDef.parseProto(response.getGraphDef());
    }

    public void commitDataLoad(Map<Long, DataLoadTarget> tableToTarget, String path) {
        CommitDataLoadRequest.Builder builder = CommitDataLoadRequest.newBuilder();
        tableToTarget.forEach(
                (tableId, target) -> {
                    builder.putTableToTarget(tableId, target.toProto());
                });
        builder.setPath(path);
        CommitDataLoadResponse response = this.stub.commitDataLoad(builder.build());
    }

    public String getMetrics(String roleNames) {
        GetMetricsResponse response =
                this.stub.getMetrics(
                        GetMetricsRequest.newBuilder().setRoleNames(roleNames).build());
        return response.getMetricsJson();
    }

    public void ingestData(String path) {
        this.stub.ingestData(IngestDataRequest.newBuilder().setDataPath(path).build());
    }

    public String loadJsonSchema(Path jsonFile) throws IOException {
        String json = new String(Files.readAllBytes(jsonFile), StandardCharsets.UTF_8);
        return loadJsonSchema(json);
    }

    public String loadJsonSchema(String json) {
        LoadJsonSchemaResponse response =
                this.stub.loadJsonSchema(
                        LoadJsonSchemaRequest.newBuilder().setSchemaJson(json).build());
        return response.getGraphDef().toString();
    }

    public int getPartitionNum() {
        GetPartitionNumResponse response =
                this.stub.getPartitionNum(GetPartitionNumRequest.newBuilder().build());
        return response.getPartitionNum();
    }

    public int createNewGraphBackup() {
        CreateNewGraphBackupResponse response =
                this.backupStub.createNewGraphBackup(
                        CreateNewGraphBackupRequest.newBuilder().build());
        return response.getBackupId();
    }

    public void deleteGraphBackup(int backupId) {
        this.backupStub.deleteGraphBackup(
                DeleteGraphBackupRequest.newBuilder().setBackupId(backupId).build());
    }

    public void purgeOldGraphBackups(int keepAliveNumber) {
        this.backupStub.purgeOldGraphBackups(
                PurgeOldGraphBackupsRequest.newBuilder()
                        .setKeepAliveNumber(keepAliveNumber)
                        .build());
    }

    public void restoreFromGraphBackup(
            int backupId, String metaRestorePath, String storeRestorePath) {
        this.backupStub.restoreFromGraphBackup(
                RestoreFromGraphBackupRequest.newBuilder()
                        .setBackupId(backupId)
                        .setMetaRestorePath(metaRestorePath)
                        .setStoreRestorePath(storeRestorePath)
                        .build());
    }

    public boolean verifyGraphBackup(int backupId) {
        VerifyGraphBackupResponse response =
                this.backupStub.verifyGraphBackup(
                        VerifyGraphBackupRequest.newBuilder().setBackupId(backupId).build());
        boolean suc = response.getIsOk();
        if (!suc) {
            logger.info("verify backup [" + backupId + "] failed, " + response.getErrMsg());
        }
        return suc;
    }

    public List<BackupInfo> getGraphBackupInfo() {
        GetGraphBackupInfoResponse response =
                this.backupStub.getGraphBackupInfo(GetGraphBackupInfoRequest.newBuilder().build());
        return response.getBackupInfoListList().stream()
                .map(BackupInfo::parseProto)
                .collect(Collectors.toList());
    }

    public ResultSet submitQuery(String query) {
        Client gremlinClient = this.gremlinClient.gremlinClient();
        return gremlinClient.submit(query);
    }

    @Override
    public void close() {
        try {
            if (this.channel != null) {
                this.channel.shutdown();
                this.channel.awaitTermination(3000, TimeUnit.MILLISECONDS);
            }
            this.gremlinClient.close();
        } catch (InterruptedException e) {
            // Ignore
        }
    }

    public void clearIngest() {
        this.stub.clearIngest(ClearIngestRequest.newBuilder().build());
    }

    public static MaxGraphClientBuilder newBuilder() {
        return new MaxGraphClientBuilder();
    }

    public static class MaxGraphClientBuilder {
        private List<SocketAddress> addrs;
        private String username;
        private String password;
        private List<String> gremlinHosts;
        private int gremlinPort;

        private MaxGraphClientBuilder() {
            this.addrs = new ArrayList<>();
            this.gremlinHosts = new ArrayList<>();
        }

        public MaxGraphClientBuilder addAddress(SocketAddress address) {
            this.addrs.add(address);
            return this;
        }

        public MaxGraphClientBuilder addHost(String host, int port) {
            return this.addAddress(new InetSocketAddress(host, port));
        }

        public MaxGraphClientBuilder setHosts(String hosts) {
            List<SocketAddress> addresses = new ArrayList<>();
            for (String host : hosts.split(",")) {
                String[] items = host.split(":");
                addresses.add(new InetSocketAddress(items[0], Integer.valueOf(items[1])));
            }
            this.addrs = addresses;
            return this;
        }

        public MaxGraphClientBuilder setUsername(String username) {
            this.username = username;
            return this;
        }

        public MaxGraphClientBuilder setPassword(String password) {
            this.password = password;
            return this;
        }

        public MaxGraphClientBuilder setGremlinPort(int gremlinPort) {
            this.gremlinPort = gremlinPort;
            return this;
        }

        public MaxGraphClientBuilder addGremlinHost(String host) {
            this.gremlinHosts.add(host);
            return this;
        }

        public MaxGraphClient build() {
            MultiAddrResovlerFactory multiAddrResovlerFactory =
                    new MultiAddrResovlerFactory(this.addrs);
            ManagedChannel channel =
                    ManagedChannelBuilder.forTarget("hosts")
                            .nameResolverFactory(multiAddrResovlerFactory)
                            .defaultLoadBalancingPolicy("round_robin")
                            .usePlaintext()
                            .build();
            ClientBlockingStub clientBlockingStub = ClientGrpc.newBlockingStub(channel);
            ClientWriteBlockingStub clientWriteBlockingStub =
                    ClientWriteGrpc.newBlockingStub(channel);
            ClientBackupBlockingStub clientBackupBlockingStub =
                    ClientBackupGrpc.newBlockingStub(channel);
            if (username != null && password != null) {
                BasicAuth basicAuth = new BasicAuth(username, password);
                clientBlockingStub = clientBlockingStub.withCallCredentials(basicAuth);
                clientWriteBlockingStub = clientWriteBlockingStub.withCallCredentials(basicAuth);
                clientBackupBlockingStub = clientBackupBlockingStub.withCallCredentials(basicAuth);
            }
            CloseableGremlinClient gremlinClient =
                    new CloseableGremlinClient(
                            this.gremlinHosts, this.gremlinPort, this.username, this.password);
            return new MaxGraphClient(
                    clientBlockingStub,
                    clientWriteBlockingStub,
                    clientBackupBlockingStub,
                    gremlinClient);
        }
    }
}
