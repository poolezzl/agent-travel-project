package com.agenttravel.backend.task;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public interface TaskQueue {

    void publish(TaskModels.QueueMessage message);

    List<TaskModels.QueueRecord> poll(UUID taskId, String group, String consumer, int count);

    List<TaskModels.QueueRecord> claim(UUID taskId, String group, String consumer, Duration minIdle, int count);

    void acknowledge(UUID taskId, String group, String recordId);
}
