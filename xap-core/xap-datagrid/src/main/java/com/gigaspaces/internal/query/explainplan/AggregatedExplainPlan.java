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
package com.gigaspaces.internal.query.explainplan;

import com.gigaspaces.api.ExperimentalApi;

import java.util.HashMap;
import java.util.Map;

/**
 * @author yael nahon
 * @since 12.0.1
 */
@ExperimentalApi
public class AggregatedExplainPlan implements ExplainPlan{
    private Map<String,SingleExplainPlan> plans;

    public AggregatedExplainPlan() {
        this.plans = new HashMap<String, SingleExplainPlan>();
    }

    public SingleExplainPlan getPlan(String partitionId) {
        return plans.get(partitionId);
    }

    public Map<String, SingleExplainPlan> getAllPlans() {
        return plans;
    }

    public void aggregate(SingleExplainPlan plan) {
        plans.put(plan.getPartitionId(),plan);
    }

    @Override
    public String toString() {
        StringBuilder res = new StringBuilder();
        for (Map.Entry<String, SingleExplainPlan> entry : plans.entrySet()) {
            res.append(entry.getKey()).append(": \n");
            res.append(entry.getValue()).append("\n");
        }
        return res.toString();
    }
}
