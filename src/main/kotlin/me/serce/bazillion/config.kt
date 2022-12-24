package me.serce.bazillion

import com.intellij.debugger.impl.GenericDebuggerRunner
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.RunConfigurationProducerService
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.*
import com.intellij.execution.configurations.ConfigurationTypeUtil.findConfigurationType
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.junit.JUnitUtil
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessHandlerFactory
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.annotations.OptionTag
import javax.swing.Box
import javax.swing.JComponent

class BazilConfigurationType :
  SimpleConfigurationType(
    "BazilConfiguration",
    "Bazil",
    "Bazil configuration",
    NotNullLazyValue.createConstantValue(BazilIcons.Bazil)
  ) {
  companion object {
    fun getInstance(): BazilConfigurationType {
      return findConfigurationType(BazilConfigurationType::class.java)
    }
  }

  override fun createTemplateConfiguration(project: Project): RunConfiguration {
    return BazilRunConfiguration(project, this)
  }

  override fun getOptionsClass(): Class<BazilConfigurationOptions> {
    return BazilConfigurationOptions::class.java
  }
}

class BazilConfigurationOptions : LocatableRunConfigurationOptions() {
  @get:OptionTag(nameAttribute = "bazelTarget")
  var target by string()

  @get:OptionTag(nameAttribute = "bazelFilter")
  var filter by string()
}

class BazilRunConfiguration(project: Project, factory: ConfigurationFactory) :
  LocatableConfigurationBase<BazilConfigurationOptions>(project, factory)/*,
  RunConfigurationWithSuppressedDefaultDebugAction*/ {

  override fun getOptions(): BazilConfigurationOptions {
    return super.getOptions() as BazilConfigurationOptions
  }

  fun getTarget(): String? {
    return options.target
  }

  fun setTarget(target: String) {
    options.target = target
  }

  fun getFilter(): String? {
    return options.filter
  }

  fun setFilter(filter: String) {
    options.filter = filter
  }

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
    return BazilRunState(this, environment)
  }

  override fun getConfigurationEditor(): SettingsEditor<BazilRunConfiguration> {
    return BazilRunConfigurationEditor()
  }

  class BazilRunState(private val config: BazilRunConfiguration, environment: ExecutionEnvironment) :
    CommandLineState(environment), RemoteState {
    private val isDebug = environment.executor.id == DefaultDebugExecutor.EXECUTOR_ID

    override fun startProcess(): ProcessHandler {
      val commandLine = GeneralCommandLine(
        "bazel",
        "test",
        config.getTarget(),
        config.getFilter()
      ).withWorkDirectory(config.project.basePath)
        .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
      if (isDebug) {
        commandLine.withParameters("--java_debug", "--test_arg=--wrapper_script_flag=--debug=5005")
      }
      val processHandler = ProcessHandlerFactory.getInstance().createColoredProcessHandler(commandLine)
      ProcessTerminatedListener.attach(processHandler)
      return processHandler
    }

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
      return super.execute(executor, runner)
    }

    override fun getRemoteConnection(): RemoteConnection? {
      if (!isDebug) {
        return null
      }
      return RemoteConnection(true, "localhost", "5005", false)
    }
  }

  class BazilRunConfigurationEditor : SettingsEditor<BazilRunConfiguration>() {
    private val target = JBTextField()
    private val filter = JBTextField()

    override fun resetEditorFrom(config: BazilRunConfiguration) {
      target.text = config.getTarget()
      filter.text = config.getFilter()
    }

    override fun applyEditorTo(config: BazilRunConfiguration) {
      config.setTarget(target.text)
      config.setFilter(filter.text)
    }

    override fun createEditor(): JComponent {
      val box = Box.createVerticalBox()
      box.alignmentX = 0f
      box.add(JBLabel("Target:", UIUtil.ComponentStyle.LARGE))
      box.add(target)
      box.add(JBLabel("Filter:", UIUtil.ComponentStyle.LARGE))
      box.add(filter)
      return box
    }
  }
}

class BazilDebuggerRunner : GenericDebuggerRunner() {
  override fun canRun(executorId: String, profile: RunProfile): Boolean {
    if (executorId != DefaultDebugExecutor.EXECUTOR_ID || profile !is BazilRunConfiguration) {
      return false
    }
    return true
  }

  override fun createContentDescriptor(
    state: RunProfileState,
    environment: ExecutionEnvironment
  ): RunContentDescriptor? {
    val connection = (state as BazilRunConfiguration.BazilRunState).remoteConnection ?: return null
    return attachVirtualMachine(state, environment, connection, true)
  }
}

class BazilRunConfigurationProducer : LazyRunConfigurationProducer<BazilRunConfiguration>() {
  override fun getConfigurationFactory(): ConfigurationFactory {
    return BazilConfigurationType.getInstance()
  }

  override fun setupConfigurationFromContext(
    configuration: BazilRunConfiguration,
    context: ConfigurationContext,
    sourceElement: Ref<PsiElement>
  ): Boolean {
    if (DumbService.getInstance(context.project).isDumb) {
      return false
    }
    val basePath = context.project.basePath ?: return false
    val psiLocation = context.psiLocation ?: return false
    val testClass = JUnitUtil.getTestClass(psiLocation) ?: return false
    val className = testClass.name ?: return false
    val classQualifiedName = testClass.qualifiedName ?: return false
    val module = ModuleUtil.findModuleForPsiElement(testClass) ?: return false

    val targetRoot = ModuleUtil.getModuleDirPath(module).substringAfter(basePath)
    val targetName =
      if (className.contains("IntegrationTest")) "integration-tests" else "unit-tests" // Assume naming convention
    configuration.setTarget("/$targetRoot:$targetName")

    val testMethod = JUnitUtil.getTestMethod(psiLocation)
    val testFilter =
      if (testMethod == null)
        "--test_filter=$classQualifiedName"
      else
        "--test_filter=$classQualifiedName#${testMethod.name}"
    configuration.setFilter(testFilter)
    configuration.name = testMethod?.name ?: className

    return true
  }

  override fun isConfigurationFromContext(
    configuration: BazilRunConfiguration,
    context: ConfigurationContext
  ): Boolean {
//    TODO("Not yet implemented")
    return false
  }

  override fun isPreferredConfiguration(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean {
    return isBazilProject(self.configuration.project)
  }

  override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean {
    return isBazilProject(self.configuration.project) && !other.isProducedBy(BazilRunConfigurationProducer::class.java)
  }
}

class NonBazilProducerSuppresser : StartupActivity.DumbAware {
  override fun runActivity(project: Project) {
    if (!isBazilProject(project)) {
      return
    }
    val producerService = RunConfigurationProducerService.getInstance(project)
    producerService.state.ignoredProducers.addAll(
      listOf(
        "com.intellij.execution.junit.AbstractAllInDirectoryConfigurationProducer",
        "com.intellij.execution.junit.AllInDirectoryConfigurationProducer",
        "com.intellij.execution.junit.AllInPackageConfigurationProducer",
        "com.intellij.execution.junit.TestInClassConfigurationProducer",
        "com.intellij.execution.junit.TestClassConfigurationProducer",
        "com.intellij.execution.junit.PatternConfigurationProducer",
        "com.intellij.execution.junit.UniqueIdConfigurationProducer",
        "com.intellij.execution.junit.testDiscovery.JUnitTestDiscoveryConfigurationProducer",
        "com.intellij.execution.application.ApplicationConfigurationProducer"
      )
    )
  }
}

private fun isBazilProject(project: Project): Boolean {
  return BazilSettings.getInstance(project).state.linkedSettings.isNotEmpty()
}
