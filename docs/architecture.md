# Synapsed Decentralized P2P Platform: Architecture & Requirements

## 1. Project Overview
The Synapsed platform provides a decentralized, privacy-centric peer-to-peer (P2P) application infrastructure. It enables secure, direct, and relay-based communication between users, supporting privacy-preserving authentication, decentralized identity, and compliance with modern data protection standards. The system is designed to be scalable, cost-effective, and easy to operate using AWS managed services.

**Key goals:**
- Decentralization: Minimize central points of control and failure.
- Privacy: End-to-end encryption, minimal data retention, and user-controlled identity.
- Scalability: Support for dynamic peer discovery and high concurrency.
- Compliance: Built-in controls for GDPR and other regulatory requirements.

## 2. Personas & Use Cases
**Personas:**
- **End Users:** Individuals using P2P applications for messaging, file sharing, or collaboration.
- **Application Developers:** Build and deploy privacy-centric P2P apps on the platform.
- **Administrators/Operators:** Manage platform health, compliance, and security.

**Core Use Cases:**
- Secure, private messaging between peers.
- Decentralized identity management and authentication.
- Peer discovery and connection establishment (direct or via relay).
- Real-time collaboration and data sharing.
- Compliance reporting and audit for regulated environments.

## 3. Business & Technical Requirements
**Functional Requirements:**
- Peer discovery and connection management (direct, relay, fallback).
- Secure messaging and data transfer between peers.
- Decentralized identity (DID) and privacy-preserving authentication (e.g., zero-knowledge proofs).
- Support for multiple transport layers (WebRTC, IoT Core, local network).
- Compliance logging and reporting.

**Non-Functional Requirements:**
- End-to-end encryption for all communications.
- High availability and fault tolerance.
- Low operational overhead (managed services preferred).
- Cost efficiency and transparent cost reporting.
- Performance: Low-latency peer connections.

**Regulatory/Compliance Requirements:**
- GDPR-compliant data handling and retention.
- Audit logging for all sensitive operations.
- Secure key management and access controls.

## 4. Threat Model & Privacy Considerations
**Key Threats:**
- Eavesdropping on peer communications.
- Unauthorized access to user data or metadata.
- Compromised relay or infrastructure components.
- Insider threats and privilege escalation.

**Mitigation Strategies:**
- End-to-end encryption (client-managed keys where possible).
- Use of AWS KMS for server-side key management.
- Least privilege IAM roles and resource policies.
- Audit logging and anomaly detection (CloudTrail, GuardDuty).
- Regular security reviews and automated compliance checks.

**Data Flow & Trust Boundaries:**
- All sensitive data encrypted in transit and at rest.
- Minimal data stored centrally; ephemeral data where possible.
- Clear separation between user-controlled and platform-managed resources.

## 5. High-Level Architecture
- **System Architecture Diagram:** _(To be added)_
- **Major Components:**
  - Peer Discovery Service (DynamoDB, Lambda, API Gateway)
  - Relay Service (EC2/STUN-TURN, Lambda, SQS)
  - Identity & Authentication (Custom Decentralized Identity (DID), Zero-Knowledge Proof (ZKP) verification)
  - Messaging/Data Transfer (WebRTC, IoT Core, S3 for large files)
  - Compliance & Monitoring (CloudWatch, CloudTrail, Config, GuardDuty)
- **Data Flow Overview:**
  - Peers register/discover via API Gateway & DynamoDB.
  - Direct connections use WebRTC; fallback to relay via TURN/EC2.
  - All messages encrypted end-to-end; metadata minimized.
  - Compliance logs and metrics sent to CloudWatch/Config.

## 6. Key AWS Services & Rationale
- **Lambda:** Stateless compute for peer management, signaling, and compliance automation (cost-effective, scalable).
- **DynamoDB:** Fast, serverless peer registry and state storage (scalable, low-latency).
- **IoT Core:** Alternative transport for messaging in constrained networks (managed, secure).
- **EC2 (STUN/TURN):** Relay for NAT traversal and fallback (required for some P2P scenarios).
- **S3:** Storage for large files and logs (durable, cost-effective).
- **SQS:** Decoupled messaging for signaling and event-driven workflows.
- **KMS:** Encryption key management (compliance, security).
- **CloudWatch, CloudTrail, Config, GuardDuty:** Monitoring, logging, compliance, and security automation.

> **Note:** AWS Cognito is not used in this platform due to the privacy and decentralization requirements. All identity and authentication are handled via decentralized identity (DID) and privacy-preserving protocols (e.g., zero-knowledge proofs), ensuring that user data and authentication are not centralized or managed by a third-party identity provider.

## 7. Compliance & Privacy Controls
- **Data Retention:** Ephemeral by default; configurable retention for compliance.
- **Encryption:** All data encrypted at rest (KMS) and in transit (TLS, WebRTC E2E).
- **Access Controls:** Strict IAM roles, resource policies, and audit trails.
- **Compliance Reporting:** Automated checks (Config), dashboards, and exportable audit logs.

## 8. Non-Goals / Out of Scope
- Mobile client implementation (focus is on backend/platform).
- Support for non-AWS cloud providers in this phase.
- Centralized user data storage or analytics.
- Unencrypted or unauthenticated peer connections.

## 9. References
- [Implementation Improvements](./improvements.md)
- [Compliance Stack](./05-compliance-stack.md)
- [Security Monitoring Stack](./04-security-monitoring-stack.md)
- [Logging Stack](./03-logging-stack.md)
- [Incident Response Stack](./06-incident-response-stack.md)
- [Alerting Stack](./07-alerting-stack.md)
- [Relay Stack](./10-relay-stack.md)
- [Subscription Stack](./13-subscription-stack.md)

---

*This document is a living artifact and should be updated as requirements and architecture evolve.* 