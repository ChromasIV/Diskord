package com.jessecorbett.diskord.testbot

import com.jessecorbett.diskord.bot.bot
import com.jessecorbett.diskord.bot.classicCommands
import com.jessecorbett.diskord.bot.events
import com.jessecorbett.diskord.bot.interaction.interactions
import com.jessecorbett.diskord.util.sendMessage

suspend fun main() {
    bot(System.getenv("DISKORD_JVM_BOT")) {
        events {
            var started = false
            onReady {
                if (!started) {
                    channel("545369349452726303").sendMessage("Diskord JVM bot has started")
                }
                setStatus("Making sure JVM runtime works")
                started = true
            }
        }

        classicCommands {
            command("jvm") {
                it.respondAndDelete("JVM bot is working!")
            }
        }

        interactions {
            userCommand("print") { it, data ->
                it.respond {
                    content = "Test data for interaction " + data.convertedUsersRolesChannels
                    ephemeral
                }
            }

            messageCommand("test") { interaction, data ->
                println(interaction)
            }

            slashCommand("echo", "Makes the bot say something") {
                val message by stringParameter("message", "The message")
                callback { interaction, _ ->
                    interaction.respond {
                        content = message
                    }
                }
            }
        }
    }
}
