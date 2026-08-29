**Agreement version: 1.2**

**Effective date: August 28, 2026**

Welcome to TokenFlow (the "App"). This agreement is available under About > User agreement and is not presented as a mandatory first-launch checkbox. Please read it before using network-connected features. Rights granted by the App's free and open-source licenses are independent of this agreement and are described in Section 8.

## 1. Scope

1. This agreement describes the relationship between you and the App developer when you install and use the distributed App.
2. Model inference, search, web reading, speech generation, and other external services are supplied by their respective operators. Unless the App developer expressly identifies a service as its own, your use of that service is also governed by the operator's terms, privacy policy, billing rules, and service limits.
3. External-service terms govern only your account and use of that external service. They do not reduce or replace rights granted to you under a free or open-source license covering the App or a component of it.
4. Nothing in this agreement limits consumer or other statutory rights that cannot lawfully be waived.

## 2. What the App does

1. TokenFlow is an Android AI client in which you provide service addresses and API keys. It supports model conversations, attachments, conversation management, saved messages, notes, agents, local knowledge, web search, web reading, and speech generation.
2. Models, search results, web content, and generated speech come from services that you select, configure, or enable. The App developer does not control their content, prices, quotas, or availability unless expressly stated otherwise.
3. Some optional tools can be invoked by you or by a configured model during a conversation. A separate confirmation may not be shown before every invocation.
4. Features and compatibility can change between releases. No promise is made that every external service will remain available, free, or compatible.
5. Infinite Cloud lets the App connect directly to a Linux server by SSH and lets a configured model run shell commands, Python, JavaScript, file operations, background tasks, and MCP tools. Once enabled, these operations can run without confirmation for each command.

## 3. API keys, accounts, and charges

1. You are responsible for obtaining accounts and credentials lawfully and for having permission to use each configured endpoint.
2. Protect API keys, SSH private keys and passphrases, MCP secrets, archive passwords, and device access. If a credential may have been exposed, revoke or replace it at the corresponding provider or server.
3. The App uses Android security facilities to protect configured secrets and encrypts configuration archives with the password you choose. No security measure can eliminate all risks of disclosure, device loss, or data loss.
4. Subscription fees, usage charges, network costs, taxes, and refunds are matters between you and the relevant service operator.

## 4. Your content

1. "Your content" includes prompts, system instructions, conversations, images, documents, URLs, saved messages, notes, agent settings, knowledge materials, and other data you enter, import, capture, save, or select for processing.
2. You retain the rights you hold in your content. To carry out your instructions, the App processes it locally and sends only the material needed for the selected feature to the services you configure.
3. This agreement does not require you to grant the App developer a permanent, exclusive, or sublicensable license to your content.
4. You are responsible for having a lawful basis and any required permissions to process personal information, confidential material, copyrighted works, and other protected content.

## 5. Local storage and data transfers

1. Conversations, saved messages, notes, agents, knowledge files, and related settings are primarily stored in the App's private storage. The App does not currently provide cloud synchronization or recovery of conversation data.
2. When you use a feature, relevant data can leave the device as follows:
   - An API key is sent as authentication to the service endpoint you configure. A custom endpoint operator will receive the corresponding credential and request content.
   - Conversation history, system instructions, attachment content or descriptions, and retrieved knowledge passages can be sent to the configured model provider.
   - Note content can be sent to a model you choose to generate a title or rewrite the note.
   - Search queries and parameters are sent to Exa when web search is used.
   - A URL and related request data can be sent to InfoFlow, sent to the target website, or fetched directly by the App.
   - Search results and extracted web content can be forwarded to the configured model as tool results.
   - Text selected for speech and speech parameters are sent to Xiaomi MiMo.
   - When Infinite Cloud is enabled, every attachment on the current user message is uploaded to the selected Linux server before model generation. Commands, scripts, file content, task metadata, and logs can also be transferred over SSH.
   - MCP tool arguments and results are exchanged with the configured remote MCP process or endpoint. Configured MCP environment values or HTTP headers are disclosed to that process or endpoint as required to connect.
3. External operators can log, process, retain, or transfer received data under their own policies. Review those policies before enabling a service.
4. Temporary results such as audio files can be stored in the App cache and removed by Android or the App. Exported encrypted configuration files are under your control outside private App storage.
5. Deleting local content or uninstalling the App affects only copies controlled by the App. It does not delete copies retained by external services, on an Infinite Cloud server, or in files you exported. Deleting a server configuration does not delete its remote task directories.

## 6. External services and links

1. Provider application links, project links, and web links are supplied for convenience and do not constitute a warranty or endorsement.
2. External operators can change interfaces, models, terms, regional access, authentication, prices, or quotas, and can interrupt or end a service.
3. Problems caused by configuration, account status, quota, network conditions, or an external-service change are subject to that operator's rules. A later App release may or may not restore compatibility.
4. Obligations imposed by an external operator apply to your use of that operator's service. They do not impose additional conditions on using, studying, modifying, or distributing free and open-source App code under its applicable license.

## 7. AI output and risk

1. Generated text, search summaries, note rewrites, and other output can be inaccurate, incomplete, outdated, biased, or inconsistent with third-party rights.
2. AI output is not medical, legal, financial, investment, employment, admissions, safety, or other professional advice and should not be the sole basis for a high-risk decision.
3. Check output against reliable sources and qualified professionals where appropriate. You are responsible for deciding whether output is suitable for your intended use.
4. Rights in output can depend on applicable law, provider terms, input rights, and the particular use. The App developer does not guarantee exclusive or complete rights in any output.
5. Infinite Cloud does not add a command blacklist, directory sandbox, `sudo` block, or per-command approval. The selected Unix account is the only permission boundary. A model can make destructive changes, disclose data, consume paid resources, access networks, or execute malicious instructions within that account's permissions. Use a dedicated least-privilege account and review the server and MCP software you enable.

## 8. Free and open-source licenses

1. Except for third-party material that is clearly identified, code, documentation, and visual assets in the TokenFlowApp repository for which the project maintainers hold the necessary licensing rights are provided under the Apache License, Version 2.0. The license text is available at <https://github.com/mekwenby/TokenFlowApp/blob/main/LICENSE>.
2. Subject to the Apache License 2.0, you may use the covered material for any purpose, reproduce it, modify it, create derivative works, publicly display or perform it, sublicense it, and distribute it. This agreement adds no restriction to those license rights.
3. Third-party components and materials remain subject to their own copyright notices and licenses. Their coordinates, notices, and license texts are available offline under About > Third-party notices.
4. If this agreement conflicts with an applicable free or open-source license, the license controls for the covered material.
5. Apache License 2.0 does not grant permission to use the TokenFlow or 一念通流 names, logos, or other source identifiers as trademarks, except as required for reasonable and customary description of the origin of the work.

## 9. Changes, availability, and stopping use

1. Future versions can be changed, discontinued, or left without maintenance or technical support because of feature work, security risks, legal requirements, or external-service changes.
2. You can stop using network-connected features or uninstall the App at any time. Current configuration export includes providers and keys, models, some global settings, Exa and MiMo settings, agents, and non-sensitive Infinite Cloud server/MCP definitions with pinned host fingerprints. It never includes SSH private keys or passphrases, MCP environment values, or MCP HTTP header values. It does not include conversations, messages, attachments, saved messages, notes, knowledge files, avatars, or display preferences.
3. The current App does not provide a complete export, backup, or cloud recovery mechanism for all local workspace data. Uninstalling, system cleanup, or device failure can make that data unrecoverable.

## 10. Disclaimers and liability

1. To the extent permitted by applicable law, the App is provided on an "AS IS" basis. The App developer aims to maintain the client but does not guarantee uninterrupted operation, compatibility with external services, preservation of data, or freedom from security incidents.
2. Responsibility for loss directly caused by an external service, configuration, network, device state, your content, or reliance on AI output is determined under applicable law and the circumstances of the case.
3. No disclaimer or limitation in this agreement excludes liability that cannot lawfully be excluded or limits mandatory consumer, privacy, personal-information, or other statutory rights.
4. License-specific warranty disclaimers and liability limitations continue to apply to material distributed under the corresponding free or open-source license.

## 11. Minors

1. A person who has not reached the age of independent consent or legal capacity in their location should use the App only with guidance and consent from a parent or guardian where required.
2. Guardians should help assess external services, content, AI output, accounts, API keys, charges, and data-transfer risks.
3. A feature must not be used by a minor where applicable law or an external-service rule prohibits that use.

## 12. Agreement updates

1. This agreement can be updated when features, data handling, external services, or legal requirements change. The current text will be provided in About > User agreement or on the App's release page.
2. Material changes will be highlighted or presented for separate consent when applicable law requires it.
3. An update applies from its stated effective date to the matters governed by this agreement. It does not retroactively alter or revoke rights already granted under an open-source license.

## 13. Governing law and disputes

1. This agreement is subject to mandatory law that applies in your location and cannot be excluded by contract.
2. You can first contact the App developer about a dispute. Either party can then use a court, consumer authority, regulator, or other competent dispute-resolution body as permitted by law.

## 14. Contact

For questions about this agreement, the App, or data handling, use the developer contact listed by the distribution platform from which you obtained the App, or visit the current project page: <https://github.com/mekwenby/TokenFlowApp>.
