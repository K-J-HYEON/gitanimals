package org.gitanimals.render.app

import jakarta.annotation.PostConstruct
import org.gitanimals.render.domain.AnimationMode
import org.gitanimals.render.domain.User
import org.gitanimals.render.domain.UserService
import org.gitanimals.render.domain.event.Visited
import org.rooftop.netx.api.*
import org.springframework.stereotype.Service

@Service
class AnimationFacade(
    private val userService: UserService,
    private val contributionApi: ContributionApi,
    private val orchestratorFactory: OrchestratorFactory,
    private val sagaManager: SagaManager,
) {

    private lateinit var registerNewUserOrchestrator: Orchestrator<String, User>

    fun getSvgAnimation(username: String, mode: String): String {
        val animationMode = AnimationMode.valueOf(mode.uppercase())
        return when (userService.existsByName(username)) {
            true -> {
                val svgAnimation = userService.getSvgAnimationByUsername(username, animationMode)
                sagaManager.startSync(Visited(username))
                svgAnimation
            }

            false -> {
                registerNewUserOrchestrator.sagaSync(10000, username)

                userService.getSvgAnimationByUsername(username, animationMode)
            }
        }
    }

    @PostConstruct
    fun registerNewUserOrchestrator() {
        registerNewUserOrchestrator = orchestratorFactory.create<String>("register new user")
            .startWithContext(
                contextOrchestrate = { context, username ->
                    context.set("username", username)
                    contributionApi.getAllContributionYears(username)
                }
            )
            .joinWithContext(
                contextOrchestrate = object : ContextOrchestrate<List<Int>, Map<Int, Int>> {
                    override fun orchestrate(context: Context, request: List<Int>): Map<Int, Int> {
                        val username = context.decodeContext("username", String::class)
                        return contributionApi.getContributionCount(username, request)
                    }

                    override fun reified(): TypeReference<List<Int>> {
                        return object : TypeReference<List<Int>>() {}
                    }
                }
            )
            .commitWithContext(
                contextOrchestrate = object : ContextOrchestrate<Map<Int, Int>, User> {
                    override fun orchestrate(context: Context, request: Map<Int, Int>): User {
                        val username = context.decodeContext("username", String::class)
                        return userService.createNewUser(username, request)
                    }

                    override fun reified(): TypeReference<Map<Int, Int>> {
                        return object : TypeReference<Map<Int, Int>>() {}
                    }
                }
            )
    }
}
