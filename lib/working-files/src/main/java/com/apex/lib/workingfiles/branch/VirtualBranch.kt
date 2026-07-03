package com.apex.lib.workingfiles.branch

import kotlinx.serialization.Serializable

/**
 * 虚拟分支 — Apex 独有的"假设性"分支系统。
 *
 * **创新点**（VSCode/Cline/Aider 都没有）：
 *   - 不依赖 git（移动端 git 难用）
 *   - 不真正复制文件，而是基于快照的"指针"
 *   - 同一文件可以同时存在多个分支，互不干扰
 *   - 任意时刻可以合并 / 丢弃分支
 *   - 跨 APK 共享（其他 Agent 可以看到分支）
 *
 * **典型用法**：
 *   1. 用户对当前代码不满意，但想"试试"另一种实现
 *   2. 创建虚拟分支 "experiment-1"，Agent 在分支上修改
 *   3. 主分支（main）保持不变，用户可以对比
 *   4. 满意 → 合并到 main；不满意 → 丢弃分支
 *
 * **与 git 分支的区别**：
 *   - git: 操作整个仓库，文件系统级别
 *   - Apex 虚拟分支: 单文件级别，更细粒度
 *   - git: 需要 working directory 切换
 *   - Apex: 通过快照指针，无切换成本
 *
 * @property id 分支 ID
 * @property name 分支名（用户可读）
 * @property filePath 关联的文件路径
 * @property baseSnapshotId 基线快照（从哪个快照分叉）
 * @property headSnapshotId 当前 HEAD 快照
 * @property createdAt 创建时间
 * @property status 分支状态
 * @property description 分支描述
 * @property agentId 创建此分支的 Agent（可选）
 * @property color 显示颜色（UI 用，hex 字符串如 "#FF5722"）
 */
@Serializable
data class VirtualBranch(
    val id: String,
    val name: String,
    val filePath: String,
    val baseSnapshotId: String,
    var headSnapshotId: String,
    val createdAt: Long,
    var updatedAt: Long,
    val status: BranchStatus = BranchStatus.ACTIVE,
    val description: String = "",
    val agentId: String? = null,
    val color: String = "#007ACC"
) {
    /** 是否已合并。 */
    val isMerged: Boolean get() = status == BranchStatus.MERGED

    /** 是否已丢弃。 */
    val isDiscarded: Boolean get() = status == BranchStatus.DISCARDED

    /** 是否活跃。 */
    val isActive: Boolean get() = status == BranchStatus.ACTIVE
}

/** 分支状态。 */
@Serializable
enum class BranchStatus(val displayName: String) {
    ACTIVE("活跃"),       // 可继续编辑
    MERGED("已合并"),     // 已合并到 main
    DISCARDED("已丢弃"),  // 已丢弃
    LOCKED("已锁定")      // 临时锁定（如等待 Agent 完成）
}

/**
 * 分支合并冲突。
 *
 * 当分支与 main 都有新变更时，合并可能产生冲突。
 * Apex 提供 3 种合并策略：
 *   - [MERGE_KEEP_BRANCH]: 保留分支版本（覆盖 main）
 *   - [MERGE_KEEP_MAIN]: 保留 main 版本（丢弃分支变更）
 *   - [MERGE_MANUAL]: 手动解决（生成冲突标记）
 */
@Serializable
data class BranchMergeConflict(
    val branchId: String,
    val mainSnapshotId: String,
    val branchSnapshotId: String,
    val conflictLines: Int,  // 冲突行数
    val strategy: MergeStrategy = MergeStrategy.MERGE_MANUAL
)

/** 合并策略。 */
@Serializable
enum class MergeStrategy(val displayName: String) {
    MERGE_KEEP_BRANCH("保留分支版本"),
    MERGE_KEEP_MAIN("保留主版本"),
    MERGE_MANUAL("手动解决")
}

/**
 * 分支合并结果。
 */
@Serializable
data class BranchMergeResult(
    val success: Boolean,
    val branchId: String,
    val newSnapshotId: String? = null,
    val conflict: BranchMergeConflict? = null,
    val message: String = ""
)
