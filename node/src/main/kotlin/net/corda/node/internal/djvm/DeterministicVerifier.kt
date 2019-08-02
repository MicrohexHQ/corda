package net.corda.node.internal.djvm

import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.crypto.SecureHash
import net.corda.core.internal.ContractVerifier
import net.corda.core.internal.Verifier
import net.corda.core.transactions.LedgerTransaction
import net.corda.djvm.SandboxConfiguration
import net.corda.djvm.analysis.AnalysisConfiguration
import net.corda.djvm.execution.*
import net.corda.djvm.messages.Message
import net.corda.djvm.source.ClassSource

class DeterministicVerifier(
    ltx: LedgerTransaction,
    transactionClassLoader: ClassLoader,
    private val analysisConfiguration: AnalysisConfiguration
) : Verifier(ltx, transactionClassLoader) {

    override fun verifyContracts() {
        val configuration = SandboxConfiguration.createFor(
            analysisConfiguration = analysisConfiguration,
            profile = ExecutionProfile.DEFAULT,
            enableTracing = false
        )
        val verifierClass = ClassSource.fromClassName(ContractVerifier::class.java.name)
        val result = IsolatedTask(verifierClass.qualifiedClassName, configuration).run {
            val executor = Executor(classLoader)

            val sandboxTx = ltx.transform { componentGroups, serializedInputs, serializedReferences ->
            }

            val verifier = classLoader.loadClassForSandbox(verifierClass).newInstance()

            // Now execute the contract verifier task within the sandbox...
            executor.execute(verifier, sandboxTx)
        }

        result.exception?.run {
            val sandboxEx = SandboxException(
                Message.getMessageFromException(this),
                result.identifier,
                verifierClass,
                ExecutionSummary(result.costs),
                this
            )
            logger.error("Error validating transaction ${ltx.id}.", sandboxEx)
            throw DeterministicVerificationException(ltx.id, sandboxEx.message ?: "", sandboxEx)
        }
    }

    @Throws(Exception::class)
    override fun close() {
        analysisConfiguration.closeAll()
    }
}

class DeterministicVerificationException(id: SecureHash, message: String, cause: Throwable)
    : TransactionVerificationException(id, message, cause)
