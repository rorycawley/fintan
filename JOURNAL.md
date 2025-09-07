# 7th Sep 2025

I'm clarifying what an agent is. Reading [simple python decision agent](https://www.anupshinde.com/simple-decision-agent-python/) I can see that an agent is something that can *Observe* its environment, *Think* based on rules or logic and *Act* to solve a problem or achieve a goal.

Non-LLM agents don't understand natural language and cannot adapt or learn. On the other hand, LLM agents can cope with vague natural language requests although the results aren't fully predictable. LLM agents can ask follow up questions to clarify the request and that can lead to better results.

Reading [LLM-Powered Agent](https://www.anupshinde.com/llm-powered-agent-python/) I can see the benefits of having an LLM at the core, it can take vague asks and turn them into canonical data that a pure logic decision maker can use. I also saw how to use a system prompt and how to shape the response with a JSON schema.

Reading [AI Agent asking clarifying questions](https://www.anupshinde.com/clarifying-ai-agent-python/) I can see that the LLM can ask clarifying questions until everything is clear and understood. The LLM can detect what is vague or missing and ask one clarifying question at a time, looping until all of the data it know it needs is confirmed. What's good about this is that it reduces silent errors. This also introduces the ```xml
<DONE/>
``` sentinel.
