import com.microsoft.z3.Context
import com.microsoft.z3.Optimize
import com.microsoft.z3.Status
import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.runner.core.KsmtWorkerArgs
import io.ksmt.runner.core.KsmtWorkerFactory
import io.ksmt.runner.core.KsmtWorkerPool
import io.ksmt.runner.core.RdServer
import io.ksmt.runner.core.WorkerInitializationFailedException
import io.ksmt.runner.generated.models.TestProtocolModel
import io.ksmt.solver.bitwuzla.KBitwuzlaSolver
import io.ksmt.solver.bitwuzla.KBitwuzlaSolverConfiguration
import io.ksmt.solver.maxsmt.solvers.KPMResSolver
import io.ksmt.solver.maxsmt.test.parseMaxSMTTestInfo
import io.ksmt.sort.KBoolSort
import io.ksmt.test.TestRunner
import io.ksmt.test.TestWorker
import io.ksmt.test.TestWorkerProcess
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.extension
import kotlin.io.path.notExists
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TestConverter {
    private lateinit var Z3_ctx: Context
    private val ctx: KContext = KContext()
    private lateinit var maxSATSolver: KPMResSolver<KBitwuzlaSolverConfiguration>
    private lateinit var maxSMTSolver: Optimize
    private val ignoreNotProcessed = true
    private val executor = Executors.newCachedThreadPool()

    fun convertTestZ3API(testPath: Path, maxSMTTestInfoPath: Path, theoryName: String, reportsDir: File) {
        require(testPath.extension == "smt2") {
            "Test file extension must be `smt2` but was [${testPath.extension}] must exist"
        }
        require(maxSMTTestInfoPath.extension == "maxsmt") {
            "MaxSMT test information extension must be `maxsmt` but was [${maxSMTTestInfoPath.extension}] must exist"
        }

        val notSucceededFile = File(reportsDir.toPath().toString() + File.separator + "not_succeeded_$theoryName.txt")
        if (!notSucceededFile.exists()) {
            notSucceededFile.createNewFile()
        }

        if (ignoreNotProcessed && checkFilePathIsAlreadyInTheList(notSucceededFile.toPath(), testPath)) {
            println("[INFO] [$testPath] ignored")
            return
        }

        if (isTestAlreadyConverted(maxSMTTestInfoPath.toFile())) {
            println("[INFO] [$maxSMTTestInfoPath] already converted")
            return
        }

        Z3_ctx = Context()
        maxSMTSolver = Z3_ctx.mkOptimize()

//        maxSMTSolver.fromFile(testPath.toString())

        val expressions = Z3_ctx.parseSMTLIB2File(
            testPath.toString(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
        ).toList()

        println("[INFO] [$testPath] processing started")

        val maxSmtTestIntoPath = testPath.toString().removeSuffix(".smt2") + ".maxsmt"
        val maxSmtTestInfo = parseMaxSMTTestInfo(File(maxSmtTestIntoPath).toPath())

        val softConstraintsSize = maxSmtTestInfo.softConstraintsWeights.size

        val softExpressions =
            expressions.subList(
                expressions.lastIndex + 1 - softConstraintsSize,
                expressions.lastIndex + 1,
            )
        val hardExpressions =
            expressions.subList(0, expressions.lastIndex + 1 - softConstraintsSize)

        hardExpressions.forEach {
            maxSMTSolver.Assert(it)
        }

        var softConstraintsWeightsSum = 0u

        var index = 0

        maxSmtTestInfo.softConstraintsWeights
            .zip(softExpressions)
            .forEach { (weight, expr) ->
                maxSMTSolver.AssertSoft(expr, weight.toInt(), index.toString())
                softConstraintsWeightsSum += weight
                index += 1
            }

        val task = Callable {
            return@Callable maxSMTSolver.Check()
        }

        val future = executor.submit(task)

        var maxSMTResult: Status? = null

        try {
            maxSMTResult = future[300, TimeUnit.SECONDS]
        } catch (ex: TimeoutException) {
            future.cancel(true)
        } catch (ex: Exception) {
            val errorsFile = File(reportsDir.toPath().toString() + File.separator + "errors_$theoryName.txt")

            if (!errorsFile.exists()) {
                errorsFile.createNewFile()
            }

            FileOutputStream(
                errorsFile,
                true,
            ).bufferedWriter().use { writer ->
                writer.append(testPath.toString())
                writer.append(ex.message.toString() + "\n")
            }
        }

        if (maxSMTResult == null) {
            processNotSucceededTest(notSucceededFile.toPath(), testPath)
            return
        }

        val satSoftConstraintsWeightsSum = maxSmtTestInfo.softConstraintsWeights
            .zip(softExpressions)
            .fold(0uL) { acc, expr ->
                acc + if (maxSMTSolver.model.eval(expr.second, true).isTrue) expr.first.toULong() else 0uL
            }

        println(satSoftConstraintsWeightsSum)

        FileOutputStream(maxSMTTestInfoPath.toFile(), true).bufferedWriter().use { writer ->
            writer.append("$SAT_SOFT_CONSTRAINTS_WEIGHTS_SUM_STR: $satSoftConstraintsWeightsSum")
        }

        println("[INFO] [$testPath] processed")
    }

    fun convertTest(testPath: Path, maxSMTTestInfoPath: Path) {
        require(testPath.extension == "smt2") {
            "Test file extension must be `smt2` but was [${testPath.extension}] must exist"
        }
        require(maxSMTTestInfoPath.extension == "maxsmt") {
            "MaxSMT test information extension must be `maxsmt` but was [${maxSMTTestInfoPath.extension}] must exist"
        }

        val notSucceededPath =
            "C:\\Users\\fWX1139906\\github\\ksmt\\ksmt-maxsat-gen\\src\\main\\resources\\not_succeeded.txt"

        if (ignoreNotProcessed && checkFilePathIsAlreadyInTheList(File(notSucceededPath).toPath(), testPath)) {
            println("[INFO] [$testPath] ignored")
            return
        }

        maxSATSolver = KPMResSolver(ctx, KBitwuzlaSolver(ctx))

        if (isTestAlreadyConverted(maxSMTTestInfoPath.toFile())) {
            println("[INFO] [$maxSMTTestInfoPath] already converted")
            return
        }

        println("[INFO] [$testPath] processing started")
        var convertedAssertions = listOf<KExpr<KBoolSort>>()

        try {
            testWorkers.withWorker(ctx) { worker ->
                val assertions = worker.parseFile(testPath)
                // We should not process these assertions only for z3
                convertedAssertions = worker.convertAssertions(assertions)
                convertedAssertions = worker.internalizeAndConvertBitwuzla(convertedAssertions)
            }
        } catch (e: IgnoreTestException) {
            processNotSucceededTest(File(notSucceededPath).toPath(), testPath)
            return
        }

        println("[INFO] [$testPath] processed assertions")

        val maxSmtTestIntoPath = testPath.toString().removeSuffix(".smt2") + ".maxsmt"
        val maxSmtTestInfo = parseMaxSMTTestInfo(File(maxSmtTestIntoPath).toPath())

        val softConstraintsSize = maxSmtTestInfo.softConstraintsWeights.size

        val softExpressions =
            convertedAssertions.subList(
                convertedAssertions.lastIndex + 1 - softConstraintsSize,
                convertedAssertions.lastIndex + 1,
            )
        val hardExpressions =
            convertedAssertions.subList(0, convertedAssertions.lastIndex + 1 - softConstraintsSize)

        hardExpressions.forEach {
            maxSATSolver.assert(it)
        }

        var softConstraintsWeightsSum = 0u

        maxSmtTestInfo.softConstraintsWeights
            .zip(softExpressions)
            .forEach { (weight, expr) ->
                maxSATSolver.assertSoft(expr, weight)
                softConstraintsWeightsSum += weight
            }

        val maxSATResult = maxSATSolver.checkMaxSMT(180.seconds)

        if (!maxSATResult.maxSMTSucceeded) {
            processNotSucceededTest(File(notSucceededPath).toPath(), testPath)
            return
        }

        val satSoftConstraints = maxSATResult.satSoftConstraints

        val satSoftConstraintsWeightsSum = 0uL + satSoftConstraints.sumOf { it.weight.toULong() }

        FileOutputStream(maxSMTTestInfoPath.toFile(), true).bufferedWriter().use { writer ->
            writer.append("$SAT_SOFT_CONSTRAINTS_WEIGHTS_SUM_STR: $satSoftConstraintsWeightsSum")
        }

        println("[INFO] [$testPath] processed")
    }

    private fun checkFilePathIsAlreadyInTheList(pathToFileWithList: Path, testPath: Path): Boolean {
        var fileIsAlreadyIsInList = false

        File(pathToFileWithList.toUri()).forEachLine {
            if (it.contains(testPath.toString())) {
                fileIsAlreadyIsInList = true
                return@forEachLine
            }
        }

        return fileIsAlreadyIsInList
    }

    private fun KsmtWorkerPool<TestProtocolModel>.withWorker(
        ctx: KContext,
        body: suspend (TestRunner) -> Unit,
    ) = runBlocking {
        val worker = try {
            getOrCreateFreeWorker()
        } catch (ex: WorkerInitializationFailedException) {
            ignoreTest { "worker initialization failed -- ${ex.message}" }
        }
        worker.astSerializationCtx.initCtx(ctx)
        worker.lifetime.onTermination {
            worker.astSerializationCtx.resetCtx()
        }
        try {
            TestRunner(ctx, TEST_WORKER_SINGLE_OPERATION_TIMEOUT, worker).let {
                try {
                    it.init()
                    body(it)
                } finally {
                    it.delete()
                }
            }
        } catch (ex: TimeoutCancellationException) {
            ignoreTest { "worker timeout -- ${ex.message}" }
        } finally {
            worker.release()
        }
    }

    private fun processNotSucceededTest(notSucceededPath: Path, testPath: Path) {
        println("[ERROR] [$testPath] was not processed")

        if (checkFilePathIsAlreadyInTheList(notSucceededPath, testPath)) {
            return
        }

        FileOutputStream(notSucceededPath.toFile(), true).bufferedWriter().use { writer ->
            writer.append(testPath.toString() + "\n")
        }
    }

    private fun isTestAlreadyConverted(maxSmtFile: File): Boolean {
        val maxSmtPath = maxSmtFile.toPath()

        if (maxSmtPath.notExists()) {
            error("Path [$maxSmtPath] does not exist")
        }

        require(maxSmtPath.extension == "maxsmt") {
            "File extension cannot be '${maxSmtPath.extension}' as it must be 'maxsmt'"
        }

        var isAlreadyConverted = false

        maxSmtFile.forEachLine {
            if (it.contains(SAT_SOFT_CONSTRAINTS_WEIGHTS_SUM_STR)) {
                isAlreadyConverted = true
                return@forEachLine
            }
        }

        return isAlreadyConverted
    }

    // See [handleIgnoredTests]
    private inline fun ignoreTest(message: () -> String?): Nothing {
        throw IgnoreTestException(message())
    }

    class IgnoreTestException(message: String?) : Exception(message)

    companion object {
        private const val SAT_SOFT_CONSTRAINTS_WEIGHTS_SUM_STR = "sat_soft_constraints_weights_sum"

        val TEST_WORKER_SINGLE_OPERATION_TIMEOUT = 40.seconds

        private lateinit var testWorkers: KsmtWorkerPool<TestProtocolModel>

        @JvmStatic
        fun initWorkerPools() {
            testWorkers = KsmtWorkerPool(
                maxWorkerPoolSize = 4,
                workerProcessIdleTimeout = 10.minutes,
                workerFactory = object : KsmtWorkerFactory<TestProtocolModel> {
                    override val childProcessEntrypoint = TestWorkerProcess::class
                    override fun updateArgs(args: KsmtWorkerArgs): KsmtWorkerArgs = args
                    override fun mkWorker(id: Int, process: RdServer) = TestWorker(id, process)
                },
            )
        }

        @JvmStatic
        fun closeWorkerPools() {
            testWorkers.terminate()
        }
    }
}
