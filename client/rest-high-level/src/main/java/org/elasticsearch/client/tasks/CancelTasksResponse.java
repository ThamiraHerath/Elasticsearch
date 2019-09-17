/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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
package org.elasticsearch.client.tasks;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.XContentParser;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static org.elasticsearch.common.xcontent.ConstructingObjectParser.optionalConstructorArg;

public class CancelTasksResponse {

    private final NodesInfoData nodesInfoData;
    private final List<TaskOperationFailure> taskFailures = new ArrayList<>();
    private final List<ElasticsearchException> nodeFailures = new ArrayList<>();
    private final List<TaskInfo> tasks = new ArrayList<>();
    private final List<TaskGroup> taskGroups = new ArrayList<>();

    public CancelTasksResponse(NodesInfoData nodesInfoData,
                               List<TaskOperationFailure> taskFailures,
                               List<ElasticsearchException> nodeFailures) {
        this.nodesInfoData = nodesInfoData;
        if (taskFailures!= null){
            this.taskFailures.addAll(taskFailures);
        }
        if (nodeFailures!=null) {
            this.nodeFailures.addAll(nodeFailures);
        }
        this.tasks.addAll(nodesInfoData
            .getNodesInfoData()
            .stream()
            .flatMap(nodeData -> nodeData.getTasks().stream())
            .collect(toList())
        );

        this.taskGroups.addAll(buildTaskGroups());
    }

    private List<TaskGroup> buildTaskGroups() {
        Map<TaskId, TaskGroup.Builder> taskGroups = new HashMap<>();
        List<TaskGroup.Builder> topLevelTasks = new ArrayList<>();
        // First populate all tasks
        for (TaskInfo taskInfo : this.tasks) {
            taskGroups.put(taskInfo.getTaskId(), TaskGroup.builder(taskInfo));
        }

        // Now go through all task group builders and add children to their parents
        for (TaskGroup.Builder taskGroup : taskGroups.values()) {
            TaskId parentTaskId = taskGroup.getTaskInfo().getParentTaskId();
            if (parentTaskId.isSet()) {
                TaskGroup.Builder parentTask = taskGroups.get(parentTaskId);
                if (parentTask != null) {
                    // we found parent in the list of tasks - add it to the parent list
                    parentTask.addGroup(taskGroup);
                } else {
                    // we got zombie or the parent was filtered out - add it to the top task list
                    topLevelTasks.add(taskGroup);
                }
            } else {
                // top level task - add it to the top task list
                topLevelTasks.add(taskGroup);
            }
        }
        return Collections.unmodifiableList(topLevelTasks.stream().map(TaskGroup.Builder::build).collect(Collectors.toList()));
    }

    public NodesInfoData getNodesInfoData() {
        return nodesInfoData;
    }

    public List<TaskInfo> getTasks() {
        return tasks;
    }

    public Map<String, List<TaskInfo>> getPerNodeTasks() {
        return getTasks()
            .stream()
            .collect(groupingBy(TaskInfo::getNodeId));
    }

    public List<TaskOperationFailure> getTaskFailures() {
        return taskFailures;
    }

    public List<ElasticsearchException> getNodeFailures() {
        return nodeFailures;
    }

    public List<TaskGroup> getTaskGroups() {
        return taskGroups;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CancelTasksResponse)) return false;
        CancelTasksResponse response = (CancelTasksResponse) o;
        return getNodesInfoData().equals(response.getNodesInfoData()) &&
            Objects.equals(getTaskFailures(), response.getTaskFailures()) &&
            Objects.equals(getNodeFailures(), response.getNodeFailures()) &&
            Objects.equals(getTasks(), response.getTasks()) &&
            Objects.equals(getTaskGroups(), response.getTaskGroups());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getNodesInfoData(), getTaskFailures(), getNodeFailures(), getTasks(), getTaskGroups());
    }

    public static CancelTasksResponse fromXContent(final XContentParser parser) throws IOException {
        return PARSER.parse(parser, null);
    }

    @Override
    public String toString() {
        return "CancelTasksResponse{" +
            "nodesInfoData=" + nodesInfoData +
            ", taskFailures=" + taskFailures +
            ", nodeFailures=" + nodeFailures +
            ", tasks=" + tasks +
            ", taskGroups=" + taskGroups +
            '}';
    }

    private static ConstructingObjectParser<CancelTasksResponse, Void> PARSER;

    static {
        ConstructingObjectParser<CancelTasksResponse, Void> parser = new ConstructingObjectParser<>("cancel_tasks_response", true,
            constructingObjects -> {
                int i = 0;
                @SuppressWarnings("unchecked")
                List<TaskOperationFailure> tasksFailures = (List<TaskOperationFailure>) constructingObjects[i++];
                @SuppressWarnings("unchecked")
                List<ElasticsearchException> nodeFailures = (List<ElasticsearchException>) constructingObjects[i++];
                NodesInfoData nodesInfoData = (NodesInfoData) constructingObjects[i];
                return new CancelTasksResponse(nodesInfoData, tasksFailures, nodeFailures);
            });

        parser.declareObjectArray(optionalConstructorArg(), (p, c) ->
            TaskOperationFailure.fromXContent(p), new ParseField("task_failures"));
        parser.declareObjectArray(optionalConstructorArg(), (p, c) ->
            ElasticsearchException.fromXContent(p), new ParseField("node_failures"));
        parser.declareObject(optionalConstructorArg(), NodesInfoData.PARSER, new ParseField("nodes"));
        PARSER = parser;
    }
}
