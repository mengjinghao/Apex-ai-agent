package com.apex.agent.kernel.burst.utility

object UtilityTaskTemplates {

    val CLASSIFY_INTENT = UtilityPrompt(
        name = "classify_intent",
        systemPrompt = """Classify the intent into ONE word:
Categories: query, command, confirm, clarify, status
Examples:
  "what is the weather" -> query
  "open settings" -> command
  "yes correct" -> confirm
  "what do you mean" -> clarify
  "how it going" -> status
Respond with ONLY the category word.""",
        maxTokens = 16,
        temperature = 0.05f
    )

    val CLASSIFY_TASK_TYPE = UtilityPrompt(
        name = "classify_task_type",
        systemPrompt = """Classify task type into ONE word:
Categories: file, code, search, memory, analyze, execute, plan, unknown
Examples:
  "find the file config.json" -> file
  "write a function" -> code
  "what is the capital" -> search
  "remember this address" -> memory
  "check the output" -> analyze
  "click the button" -> execute
  "first we need to" -> plan
Respond with ONLY the category word.""",
        maxTokens = 16,
        temperature = 0.05f
    )

    val EXTRACT_ENTITIES = UtilityPrompt(
        name = "extract_entities",
        systemPrompt = """Extract requested fields from text as JSON.
Return ONLY a JSON object with the requested keys.
Examples:
Text: "I want to book a flight to Tokyo tomorrow"
Schema: destination, date
Output: {"destination": "Tokyo", "date": "tomorrow"}

Text: "My email is user@test.com and my name is John"
Schema: email, name
Output: {"email": "user@test.com", "name": "John"}

No explanation. Only output the JSON object.""",
        maxTokens = 128,
        temperature = 0.05f
    )

    val VALIDATE_OUTPUT = UtilityPrompt(
        name = "validate_output",
        systemPrompt = """Check if the output matches the expected format.
Respond with JSON: {"isValid": true/false, "reason": "short reason"}

Examples:
Expected: JSON object with "name" field
Output: {"name": "test"}
Result: {"isValid": true, "reason": "Valid JSON with name"}

Expected: JSON object with "name" field
Output: {"age": 25}
Result: {"isValid": false, "reason": "Missing required field: name"}

Expected: non-empty string
Output: ""
Result: {"isValid": false, "reason": "Empty output"}""",
        maxTokens = 64,
        temperature = 0.05f
    )

    val CLEAN_RESPONSE = UtilityPrompt(
        name = "clean_response",
        systemPrompt = """Clean the text by removing markdown code fences, extra whitespace, and formatting artifacts.
Return ONLY the cleaned text, no explanation.

Examples:
Input: ```json\n{"key": "value"}\n```
Output: {"key": "value"}

Input: "Here is the result:\n\nHello World  "
Output: "Hello World"

Input: **bold** and *italic* text
Output: bold and italic text""",
        maxTokens = 256,
        temperature = 0.1f
    )

    val FORMAT_FOR_CONTEXT = UtilityPrompt(
        name = "format_for_context",
        systemPrompt = """Summarize/compress the text to fit within the specified max length.
Keep all key information but remove redundancy.
Return ONLY the compressed text.

Examples:
Input: "This is a very long text with many repeated words and unnecessary details that can be removed while keeping the core meaning intact. We really need to focus on the main points only."
Max length: 50
Output: "Long text with repeated words and unnecessary details removed, keeping core meaning."

Input: "The quick brown fox jumps over the lazy dog. The fox was quick and brown. The dog was lazy."
Max length: 30
Output: "Quick brown fox jumps over lazy dog."""",
        maxTokens = 256,
        temperature = 0.1f
    )

    val EXTRACT_TOOL_RESULT = UtilityPrompt(
        name = "extract_tool_result",
        systemPrompt = """Extract the relevant result from a tool response.
Return the important data in a clean format.

Examples:
Tool: search_web
Response: {"status": "ok", "results": [{"title": "Python Tutorial", "url": "https://..."}], "total": 42}
Output: Python Tutorial (https://...)

Tool: run_command
Response: {"stdout": "Hello World\n", "stderr": "", "exitCode": 0}
Output: Hello World

Tool: read_file
Response: {"content": "file contents here", "path": "/test.txt", "size": 100}
Output: file contents here""",
        maxTokens = 128,
        temperature = 0.1f
    )

    val CATEGORIZE_CONTENT = UtilityPrompt(
        name = "categorize_content",
        systemPrompt = """Categorize the content into ONE word:
Categories: code, text, data, config, log, image, audio, video, unknown
Examples:
  "def hello(): print('hi')" -> code
  "The weather is nice" -> text
  '{"key": "value"}' -> data
  "server.port=8080" -> config
  "[ERROR] connection failed" -> log
Respond with ONLY the category word.""",
        maxTokens = 16,
        temperature = 0.05f
    )

    val SUGGEST_RECOVERY = UtilityPrompt(
        name = "suggest_recovery",
        systemPrompt = """Suggest recovery steps for the given error.
Respond with a JSON array of 1-3 short action strings.
Examples:
Error: "File not found: config.json"
Output: ["Check if config.json exists", "Verify file path", "Create default config"]

Error: "Connection timeout"
Output: ["Check network connectivity", "Retry with longer timeout", "Verify server address"]

Error: "Permission denied"
Output: ["Check file permissions", "Run with elevated privileges", "Verify user access rights"]""",
        maxTokens = 128,
        temperature = 0.1f
    )

    val GENERATE_STATUS = UtilityPrompt(
        name = "generate_status",
        systemPrompt = """Generate a one-line status update for the operation.
Format: "[result] operation description"
Examples:
Operation: "file_save"
Outcome: "success"
Output: "[OK] File saved successfully"

Operation: "api_call"
Outcome: "failed: timeout"
Output: "[FAIL] API call timed out"

Operation: "data_sync"
Outcome: "partial: 5/10 items synced"
Output: "[WARN] Data sync partial: 5/10 completed"

Operation: "search"
Outcome: "found 3 results"
Output: "[OK] Search completed: 3 results found"""",
        maxTokens = 64,
        temperature = 0.1f
    )

    val SUMMARIZE_STEP = UtilityPrompt(
        name = "summarize_step",
        systemPrompt = """Summarize a step log into ONE short sentence.
Keep only the essential action and result.
Examples:
Log: "Step 1: Searching for file config.json in directory /home/user/project. Scanning subdirectories recursively. Found at /home/user/project/src/config.json"
Output: "Found config.json in src/ directory"

Log: "Executing query: SELECT * FROM users WHERE age > 18. 42 rows returned. Query took 150ms."
Output: "Query returned 42 users over age 18"

Log: "Calling API endpoint https://api.example.com/data with GET method. Response status: 200. Response size: 2.4KB"
Output: "API call to /data succeeded (200, 2.4KB)"""",
        maxTokens = 64,
        temperature = 0.1f
    )

    val CHECK_CONDITION = UtilityPrompt(
        name = "check_condition",
        systemPrompt = """Check if the condition is true based on the context.
Respond with ONLY: true or false
Examples:
Condition: "contains error"
Context: "Operation completed successfully with no issues"
Output: false

Condition: "needs user input"
Context: "Please enter your password to continue"
Output: true

Condition: "task completed"
Context: "All 5 steps finished successfully and results saved"
Output: true""",
        maxTokens = 8,
        temperature = 0.05f
    )
}

data class UtilityPrompt(
    val name: String,
    val systemPrompt: String,
    val maxTokens: Int = 64,
    val temperature: Float = 0.1f
)
