# Synapse-D AWS Infrastructure

This project contains the AWS CDK infrastructure code for the Synapse-D platform, implemented in Java. Synapse-D is a suite of distributed applications with a focus on privacy and security.

## Prerequisites

- Java 21 or later
- Maven 3.8.1 or later
- AWS CLI configured with appropriate credentials
- AWS CDK CLI installed (`npm install -g aws-cdk`)

## Project Structure

The project follows a standard Maven structure with the following main components:

- **Lambda Functions**: Located in `src/main/java/me/synapsed/aws/lambda/`
- **CDK Stacks**: Located in `src/main/java/me/synapsed/aws/stacks/`
- **Tests**: Located in `src/test/java/me/synapsed/aws/`
- **Documentation**: Located in `docs/`

## Key Components

### Relay Server
The Relay Server provides a secure communication channel for P2P connections between Synapse-D applications. It uses WebRTC for direct peer-to-peer communication and includes subscription verification to ensure only authorized users can access the service. The implementation includes:
- WebRTC signaling message handling
- Peer connection management
- Subscription verification
- Secure message forwarding via SQS

### Subscription Management
The subscription system handles user subscriptions using Stripe for payment processing and DynamoDB for storing subscription data. It includes:
- Subscription creation and verification
- Webhook handling for subscription events
- Zero-knowledge proof verification for privacy-preserving authentication
- Subscription status tracking and management

### Security & Authentication
The platform uses Decentralized Identity (DID) for client-side user management, with zero-knowledge proof verification for privacy-preserving authentication. AWS IAM roles and policies are used for service access control.

### Monitoring & Alerting
The platform includes comprehensive monitoring and alerting capabilities:
- Enhanced monitoring with pattern analysis
- Security event processing
- Incident detection and response
- Alert routing and analytics
- Notification management and escalation

### Compliance & Logging
The platform includes robust compliance and logging features:
- Compliance reporting
- Log processing and analysis
- Security monitoring
- Audit trail management

## Getting Started

1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/synapsed-aws.git
   cd synapsed-aws
   ```

2. Build the project:
   ```bash
   mvn clean package
   ```

3. Run the tests:
   ```bash
   mvn test
   ```

4. Deploy the infrastructure:
   ```bash
   cdk deploy
   ```

## Development

The project uses AWS CDK to define infrastructure as code. The main components are:

- `SynapsedApp.java`: The entry point of the CDK application
- `SynapsedStack.java`: The main stack containing AWS resources
- Various specialized stacks in the `stacks/` directory
- Lambda functions in the `lambda/` directory

## Documentation

Detailed documentation for each component can be found in the `docs/` directory:

- `01-config-management.md`: Configuration management
- `02-security-stack.md`: Security infrastructure
- `03-logging-stack.md`: Logging infrastructure
- `04-security-monitoring-stack.md`: Security monitoring
- `05-compliance-stack.md`: Compliance infrastructure
- `06-incident-response-stack.md`: Incident response
- `07-alerting-stack.md`: Alerting infrastructure
- `08-webapp-stack.md`: Web application infrastructure
- `09-messenger-stack.md`: Messenger application infrastructure
- `10-relay-stack.md`: Relay server infrastructure
- `11-ml-stack.md`: Machine learning infrastructure
- `12-enhanced-monitoring-stack.md`: Enhanced monitoring with Substrates/Serventis
- `13-subscription-stack.md`: Subscription management infrastructure

## Contributing

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Create a Pull Request

## License

This project is licensed under the MIT License - see the LICENSE file for details. 