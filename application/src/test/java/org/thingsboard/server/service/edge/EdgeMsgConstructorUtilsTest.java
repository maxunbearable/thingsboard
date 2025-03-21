/**
 * Copyright © 2016-2025 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.edge;

import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.action.TbSaveToCustomCassandraTableNode;
import org.thingsboard.rule.engine.action.TbSaveToCustomCassandraTableNodeConfiguration;
import org.thingsboard.rule.engine.api.NodeConfiguration;
import org.thingsboard.rule.engine.aws.lambda.TbAwsLambdaNode;
import org.thingsboard.rule.engine.aws.lambda.TbAwsLambdaNodeConfiguration;
import org.thingsboard.rule.engine.rest.TbSendRestApiCallReplyNode;
import org.thingsboard.rule.engine.rest.TbSendRestApiCallReplyNodeConfiguration;
import org.thingsboard.rule.engine.telemetry.TbMsgAttributesNode;
import org.thingsboard.rule.engine.telemetry.TbMsgAttributesNodeConfiguration;
import org.thingsboard.rule.engine.telemetry.TbMsgTimeseriesNode;
import org.thingsboard.rule.engine.telemetry.TbMsgTimeseriesNodeConfiguration;
import org.thingsboard.server.common.data.rule.RuleChainMetaData;
import org.thingsboard.server.common.data.rule.RuleNode;
import org.thingsboard.server.gen.edge.v1.EdgeVersion;
import org.thingsboard.server.gen.edge.v1.UpdateMsgType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.thingsboard.server.service.edge.EdgeMsgConstructorUtils.EXCLUDED_NODES_BY_EDGE_VERSION;
import static org.thingsboard.server.service.edge.EdgeMsgConstructorUtils.IGNORED_PARAMS_BY_EDGE_VERSION;

@Slf4j
public class EdgeMsgConstructorUtilsTest {
    private static final int CONFIGURATION_VERSION = 5;

    public static final List<EdgeVersion> SUPPORTED_EDGE_VERSIONS_FOR_TESTS = Arrays.asList(
            EdgeVersion.V_4_0_0, EdgeVersion.V_3_9_0, EdgeVersion.V_3_8_0, EdgeVersion.V_3_7_0
    );

    private static final Map<NodeConfiguration, String> CONFIG_TO_NODE_NAME = Map.of(
            new TbMsgTimeseriesNodeConfiguration(), TbMsgTimeseriesNode.class.getName(),
            new TbMsgAttributesNodeConfiguration(), TbMsgAttributesNode.class.getName(),
            new TbSaveToCustomCassandraTableNodeConfiguration(), TbSaveToCustomCassandraTableNode.class.getName()
    );

    private static final Map<String, Integer> NODE_TO_CONFIG_PARAMS_COUNT = Map.of(
            TbMsgTimeseriesNode.class.getName(), 3,
            TbMsgAttributesNode.class.getName(), 5,
            TbSaveToCustomCassandraTableNode.class.getName(), 3
    );

    private static final Map<NodeConfiguration, String> CONFIG_TO_MISS_NODE_FOR_OLD_EDGE = Map.of(
            new TbSendRestApiCallReplyNodeConfiguration(), TbSendRestApiCallReplyNode.class.getName(),
            new TbAwsLambdaNodeConfiguration(), TbAwsLambdaNode.class.getName()
    );

    @Test
    public void testRuleChainMetadataUpdateMsgForOldEdgeVersions() {
        // GIVEN
        RuleChainMetaData metaData = createMetadataWithProblemNodes(CONFIG_TO_NODE_NAME);

        SUPPORTED_EDGE_VERSIONS_FOR_TESTS.forEach(edgeVersion -> {
            // WHEN
            List<RuleNode> ruleNodes = extractRuleNodesFromUpdateMsg(metaData, edgeVersion);

            // THEN
            assertRuleNodeConfig(ruleNodes, edgeVersion);
        });
    }

    @Test
    public void testRuleChainMetadataWithMissingNodeForOldEdgeVersions() {
        // GIVEN
        RuleChainMetaData metaData = createMetadataWithProblemNodes(CONFIG_TO_MISS_NODE_FOR_OLD_EDGE);

        SUPPORTED_EDGE_VERSIONS_FOR_TESTS.forEach(edgeVersion -> {
            // WHEN
            List<RuleNode> ruleNodes = extractRuleNodesFromUpdateMsg(metaData, edgeVersion);

            // THEN
            int leftNode = EXCLUDED_NODES_BY_EDGE_VERSION.containsKey(edgeVersion) ?
                    CONFIG_TO_MISS_NODE_FOR_OLD_EDGE.size() - EXCLUDED_NODES_BY_EDGE_VERSION.get(edgeVersion).size() :
                    CONFIG_TO_MISS_NODE_FOR_OLD_EDGE.size();

            Assert.assertEquals(leftNode, ruleNodes.size());
        });
    }

    private RuleChainMetaData createMetadataWithProblemNodes(Map<NodeConfiguration, String> nodeMap) {
        RuleChainMetaData ruleChainMetaData = new RuleChainMetaData();
        List<RuleNode> ruleNodes = new ArrayList<>();

        nodeMap.forEach((key, value) -> {
            RuleNode ruleNode = new RuleNode();

            ruleNode.setName(value);
            ruleNode.setType(value);
            ruleNode.setConfigurationVersion(CONFIGURATION_VERSION);
            ruleNode.setConfiguration(JacksonUtil.valueToTree(key.defaultConfiguration()));

            ruleNodes.add(ruleNode);
        });

        ruleChainMetaData.setFirstNodeIndex(0);
        ruleChainMetaData.setNodes(ruleNodes);

        return ruleChainMetaData;
    }

    private List<RuleNode> extractRuleNodesFromUpdateMsg(RuleChainMetaData metaData, EdgeVersion edgeVersion) {
        String ruleChainMetadataUpdateMsg =
                EdgeMsgConstructorUtils.constructRuleChainMetadataUpdatedMsg(UpdateMsgType.ENTITY_CREATED_RPC_MESSAGE, metaData, edgeVersion).getEntity();

        RuleChainMetaData ruleChainMetaData = JacksonUtil.fromString(ruleChainMetadataUpdateMsg, RuleChainMetaData.class, true);

        Assert.assertNotNull("RuleChainMetaData is null", ruleChainMetaData);

        return ruleChainMetaData.getNodes();
    }

    private void assertRuleNodeConfig(List<RuleNode> ruleNodes, EdgeVersion edgeVersion) {
        ruleNodes.forEach(ruleNode -> {
            int configParamCount = NODE_TO_CONFIG_PARAMS_COUNT.get(ruleNode.getType());

            boolean isOldEdgeVersion = IGNORED_PARAMS_BY_EDGE_VERSION.entrySet().stream()
                    .anyMatch(entry -> entry.getKey().equals(edgeVersion) &&
                            entry.getValue().containsKey(ruleNode.getType()));
            int expectedConfigAmount = isOldEdgeVersion ? configParamCount - 1 : configParamCount;

            Assert.assertEquals(
                    String.format("For ruleNode '%s', edgeVersion '%s", ruleNode.getName(), edgeVersion),
                    expectedConfigAmount, ruleNode.getConfiguration().size()
            );
        });
    }

}
