/*
 * Copyright 2020 Alibaba Group Holding Limited.
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

package com.alibaba.maxgraph.servers.ir;

import com.alibaba.graphscope.groot.discovery.NodeDiscovery;
import com.alibaba.graphscope.groot.store.GraphPartition;

public interface ExecutorEngine extends NodeDiscovery.Listener {

    void init();

    void addPartition(GraphPartition partition);

    void updatePartitionRouting(int partitionId, int serverId);

    void start();

    void stop();
}
