# Relay Stack

## Overview

The Relay Stack provides the infrastructure for the Synapse-D Relay Server, which enables secure peer-to-peer communication between Synapse-D applications. The Relay Server uses WebRTC for direct peer-to-peer connections and includes subscription verification to ensure only authorized users can access the service.

## Components

### Lambda Function

The Relay Server is implemented as an AWS Lambda function that:
- Handles WebRTC signaling (offer/answer/ICE candidates)
- Verifies user subscriptions using zero-knowledge proofs
- Forwards signaling messages between peers
- Manages connection state

### API Gateway

The API Gateway provides a REST API for the Relay Server:
- POST endpoint for WebRTC signaling
- CORS configuration for web clients
- Request validation
- Integration with Lambda function

### DynamoDB Tables

The stack creates DynamoDB tables for:
- Subscription proofs storage
- Connection state management
- User session tracking

### IAM Roles and Policies

IAM roles and policies are created for:
- Lambda function execution
- DynamoDB access
- CloudWatch Logs access
- API Gateway integration

## Architecture

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Client A   │     │ Relay Server│     │  Client B   │
│  (WebRTC)   │◄───►│  (Lambda)   │◄───►│  (WebRTC)   │
└─────────────┘     └─────────────┘     └─────────────┘
                           ▲
                           │
                    ┌─────────────┐
                    │  DynamoDB   │
                    │  (Tables)   │
                    └─────────────┘
```

## WebRTC Signaling Flow

1. Client A initiates a connection to Client B
2. Client A sends an offer to the Relay Server
3. Relay Server verifies Client A's subscription
4. Relay Server forwards the offer to Client B
5. Client B sends an answer to the Relay Server
6. Relay Server verifies Client B's subscription
7. Relay Server forwards the answer to Client A
8. Clients exchange ICE candidates through the Relay Server
9. Direct P2P connection is established between clients

## Subscription Verification

The Relay Server verifies user subscriptions using:
- Decentralized Identity (DID) for user identification
- Zero-knowledge proofs for privacy-preserving authentication
- DynamoDB for proof storage and verification
- Expiration checks for subscription validity

## Security Considerations

- All communication is encrypted using TLS
- Subscription verification prevents unauthorized access
- IAM roles follow the principle of least privilege
- DynamoDB tables are encrypted at rest
- API Gateway requests are validated and rate-limited

## Integration with Other Stacks

The Relay Stack integrates with:
- Security Stack: For IAM roles and security groups
- Subscription Stack: For subscription verification
- Authentication Stack: For user identity management
- Logging Stack: For operational logging

## Best Practices

1. Implement proper error handling and retries
2. Use connection pooling for DynamoDB access
3. Implement proper logging for troubleshooting
4. Use environment variables for configuration
5. Implement proper testing for the Lambda function
6. Monitor API Gateway and Lambda metrics
7. Implement proper security controls 