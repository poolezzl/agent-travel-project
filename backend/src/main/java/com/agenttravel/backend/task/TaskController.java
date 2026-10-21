package com.agenttravel.backend.task;

import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.RequestContext;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/tasks")
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class TaskController {

    private final TaskService service;
    private final TaskEventStreamService eventStream;

    public TaskController(TaskService service, TaskEventStreamService eventStream) {
        this.service = service;
        this.eventStream = eventStream;
    }

    @PostMapping
    public ResponseEntity<TaskModels.TaskView> create(@Valid @RequestBody TaskModels.CreateTaskRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        TaskModels.TaskView result = service.create(user().userId(), request, idempotencyKey);
        return ResponseEntity.status(result.reused() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    @GetMapping("/{taskId}")
    public TaskModels.TaskView get(@PathVariable UUID taskId) {
        Context context = user();
        return service.get(context.userId(), context.role(), taskId);
    }

    @PostMapping("/{taskId}/cancel")
    public TaskModels.TaskView cancel(@PathVariable UUID taskId,
            @Valid @RequestBody TaskModels.CancelTaskRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        Context context = user();
        return service.cancel(context.userId(), context.role(), taskId, request, idempotencyKey);
    }

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID taskId,
            @RequestParam(name = "after_sequence_no", required = false) Long afterSequence,
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
        Context context = user();
        long sequence = afterSequence != null ? afterSequence : lastEventId == null ? 0 : lastEventId;
        return eventStream.subscribe(context.userId(), context.role(), taskId, sequence);
    }

    private Context user() {
        RequestContext.Context context = RequestContext.get();
        if (context == null || context.userId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
        }
        return new Context(context.userId(), context.role());
    }

    private record Context(UUID userId, String role) {
    }
}
