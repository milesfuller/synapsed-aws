import * as cdk from 'aws-cdk-lib';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';
import { Construct } from 'constructs';

export class RelayStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    // Create DynamoDB table for peer connections
    const peerConnectionsTable = new dynamodb.Table(this, 'PeerConnections', {
      partitionKey: { name: 'did', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'peerId', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      timeToLiveAttribute: 'ttl',
      removalPolicy: cdk.RemovalPolicy.DESTROY, // For development - change for production
    });

    // Create Lambda function for handling peer connections
    const peerConnectionHandler = new lambda.Function(this, 'PeerConnectionHandler', {
      runtime: lambda.Runtime.JAVA_11,
      handler: 'me.synapsed.aws.lambda.PeerConnectionHandler::handleRequest',
      code: lambda.Code.fromAsset('../target/synapsed-aws-1.0.jar'),
      environment: {
        PEER_CONNECTIONS_TABLE: peerConnectionsTable.tableName,
      },
      timeout: cdk.Duration.seconds(30),
      memorySize: 512,
    });

    // Grant Lambda function access to DynamoDB table
    peerConnectionsTable.grantReadWriteData(peerConnectionHandler);

    // Create API Gateway
    const api = new apigateway.RestApi(this, 'RelayApi', {
      restApiName: 'Relay Service',
      description: 'API for WebRTC relay service',
    });

    // Add peer connection endpoints
    const peers = api.root.addResource('peers');
    const connect = peers.addResource('connect');
    const disconnect = peers.addResource('disconnect');

    // Add methods
    connect.addMethod('POST', new apigateway.LambdaIntegration(peerConnectionHandler));
    disconnect.addMethod('POST', new apigateway.LambdaIntegration(peerConnectionHandler));

    // Output the API endpoint URL
    new cdk.CfnOutput(this, 'RelayApiEndpoint', {
      value: api.url,
      description: 'Relay API Endpoint',
    });
  }
} 