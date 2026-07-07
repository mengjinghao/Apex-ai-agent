package com.apex.agent.orchestration.collaboration

import com.apex.agent.common.result.Result
import com.apex.agent.domain.entity.AgentMessage
import com.apex.agent.domain.entity.Task
import com.apex.agent.domain.repository.TaskRepository
import com.apex.agent.infrastructure.eventbus.EventBus
import com.apex.agent.orchestration.agent.AgentManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CollaborationEngineTest {

    private lateinit var taskRepository: FakeTaskRepository
    private lateinit var eventBus: FakeEventBus
    private lateinit var lifecycleManager: TaskLifecycleManager
    private lateinit var supervisorExecutor: FakeTaskExecutor
    private lateinit var engine: CollaborationEngine

    private val startedTaskIds = mutableListOf<String>()

    @Before
    fun setup() {
        taskRepository = FakeTaskRepository()
        eventBus = FakeEventBus()
        lifecycleManager = TaskLifecycleManager(taskRepository, eventBus)
        supervisorExecutor = FakeTaskExecutor()
        engine = CollaborationEngine(
            taskRepository = taskRepository,
            agentManager = AgentManager(),
            eventBus = eventBus,
            lifecycleManager = lifecycleManager,
            supervisorExecutor = supervisorExecutor,
            serialExecutor = FakeTaskExecutor(),
            parallelExecutor = FakeTaskExecutor(),
            debateExecutor = FakeTaskExecutor(),
            freeExecutor = FakeTaskExecutor()
        )
    }

    @After
    fun tearDown() {
        startedTaskIds.forEach { runBlocking { engine.stopTask(it) } }
    }

    @Test
    fun createTask_savesTaskAndPublishesTaskCreatedEvent() = runBlocking {
        val task = Task(
            id = "",
            title = "Test Task",
            description = "A test task",
            status = "",
            collaborationMode = "supervisor",
            agentIds = emptyList(),
            createdAt = 0L,
            updatedAt = 0L
        )

        val result = engine.createTask(task)

        assertTrue(result is Result.Success)
        val taskId = (result as Result.Success).data
        assertTrue(taskId.isNotEmpty())

        val saved = taskRepository.getById(taskId)
        assertTrue(saved is Result.Success)
        assertEquals(TaskState.PENDING.name, (saved as Result.Success).data.status)

        assertTrue(eventBus.published.any { it is CollaborationEvent.TaskCreated && it.taskId == taskId })
    }

    @Test
    fun startTask_startsExecutionAndPublishesTaskStartedEvent() = runBlocking {
        val task = Task(
            id = "task-start-1",
            title = "Startable Task",
            description = "Will be started",
            status = TaskState.PENDING.name,
            collaborationMode = "supervisor",
            agentIds = emptyList(),
            createdAt = 0L,
            updatedAt = 0L
        )
        taskRepository.save(task)

        val result = engine.startTask(task.id)
        startedTaskIds.add(task.id)

        assertEquals(Result.Success(Unit), result)
        assertTrue(supervisorExecutor.executionLatch.await(1, TimeUnit.SECONDS))
        assertTrue(supervisorExecutor.executedTasks.any { it.id == task.id })
        assertTrue(eventBus.published.any { it is CollaborationEvent.TaskStarted && it.taskId == task.id })
    }

    @Test
    fun submitMessage_publishesMessageSubmittedEvent() = runBlocking {
        val taskId = "task-msg-1"
        val message = AgentMessage(
            id = "msg-1",
            senderId = "user",
            receiverId = "agent-1",
            content = "hello",
            timestamp = 0L
        )

        val result = engine.submitMessage(taskId, message)

        assertEquals(Result.Success(Unit), result)
        assertTrue(eventBus.published.any { it is CollaborationEvent.MessageSubmitted && it.taskId == taskId })
    }

    private class FakeTaskRepository : TaskRepository {
        private val tasks = mutableMapOf<String, Task>()

        override suspend fun save(task: Task): Result<Unit> {
            tasks[task.id] = task
            return Result.Success(Unit)
        }

        override suspend fun update(task: Task): Result<Unit> {
            tasks[task.id] = task
            return Result.Success(Unit)
        }

        override suspend fun getById(id: String): Result<Task> {
            val task = tasks[id]
            return if (task != null) Result.Success(task) else Result.Failure(NoSuchElementException("No task $id"))
        }

        override suspend fun list(): Result<List<Task>> = Result.Success(tasks.values.toList())

        override suspend fun delete(id: String): Result<Unit> {
            tasks.remove(id)
            return Result.Success(Unit)
        }
    }

    private class FakeEventBus : EventBus {
        private val bus = kotlinx.coroutines.flow.MutableSharedFlow<Any>(extraBufferCapacity = 100)
        val published = mutableListOf<Any>()

        override fun <T : Any> publish(event: T) {
            published.add(event)
            bus.tryEmit(event)
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> subscribe(eventClass: Class<T>): SharedFlow<T> {
            return bus.filterIsInstance(eventClass).asSharedFlow() as SharedFlow<T>
        }
    }

    private class FakeTaskExecutor : TaskExecutor {
        val executedTasks = mutableListOf<Task>()
        val executionLatch = CountDownLatch(1)

        override suspend fun execute(task: Task): Flow<Result<Task>> = flow {
            executedTasks.add(task)
            executionLatch.countDown()
            emit(Result.Success(task.copy(status = TaskState.RUNNING.name)))
        }

        override suspend fun pause(taskId: String): Result<Unit> = Result.Success(Unit)
        override suspend fun resume(taskId: String): Result<Unit> = Result.Success(Unit)
        override suspend fun cancel(taskId: String): Result<Unit> = Result.Success(Unit)
    }
}
