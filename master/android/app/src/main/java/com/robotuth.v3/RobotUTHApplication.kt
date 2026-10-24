package com.robotuth.v3

import android.app.Application
import android.os.Bundle
import android.util.Log
import com.ainirobot.agent.AppAgent
import com.ainirobot.agent.action.Action
import com.ainirobot.agent.action.Actions

/**
 * Registers RobotUTH with Orion AgentOS (same pattern as AgentRole / agent.sample).
 *
 * On OS-R-SG04, apps that are **not** AppAgent clients cause agentservice LiveKit
 * to glitch ("pip pip") as soon as they become foreground — even with zero audio code.
 */
class RobotUTHApplication : Application() {

    lateinit var appAgent: AppAgent
        private set

    override fun onCreate() {
        super.onCreate()
        appAgent = object : AppAgent(this@RobotUTHApplication) {
            override fun onCreate() {
                setPersona(
                    "Bạn là RobotUTH, trợ lý robot tại trường. " +
                        "Trả lời ngắn gọn, thân thiện bằng tiếng Việt."
                )
                registerAction(Actions.SAY)
                Log.i(TAG, "AppAgent registered")
            }

            override fun onExecuteAction(action: Action, params: Bundle?): Boolean {
                // false = let AgentOS continue default handling
                return false
            }
        }
    }

    companion object {
        private const val TAG = "RobotUTHApp"
    }
}
