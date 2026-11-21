# IQTest

New players are frozen and untouchable until they finish a 15-question multiple-choice IQ test.

- IQ range -52 to 108, scaled by how many questions you get right.
- Negative IQ (0-4 correct out of 15) = instant ban with a custom message.
- Finished players are saved in `plugins/IQTest/playerdata.yml` and never retake the test.
- Lower IQ = slower walking, shorter chat messages, chat cooldown, no sprinting (see `tiers` in config.yml).
- Everyone sees a styled announcement in chat when someone finishes.

## Commands

| Command | Who | What |
|---|---|---|
| `/iq` | everyone | Show your IQ (or reopen the test if you're still locked) |
| `/iq top` | everyone | Top 5 smartest players |
| `/iq check <player>` | `iqtest.admin` | Look up someone's IQ |
| `/iq reset <player>` | `iqtest.admin` | Wipe their IQ so they retake the test |
| `/iq reload` | `iqtest.admin` | Reload config.yml and questions.yml |

## Permissions

- `iqtest.admin` (default: op)
- `iqtest.bypass` (default: nobody) - skips the test entirely

Ops are NOT exempt, so you will take the test too. To test again: `/iq reset <yourname>`.
If you get banned: `/pardon <name>` then `/iq reset <name>`.

## Files

- `config.yml` - IQ range, tiers/limits, all messages (MiniMessage), ban reason
- `questions.yml` - the questions; add or edit freely, then `/iq reload`

## Building

`mvn package` (JDK 21), or push to GitHub and use the Actions workflow.
Jar: `target/IQTest.jar`. If your server isn't Paper 1.21.4, change `<paper.version>` in `pom.xml`.
