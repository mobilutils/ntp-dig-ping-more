#!/bin/bash
SERVER="http://192.168.1.42:3000/mcp"

# Step 1: Initialize & extract session ID
echo "🔄 Initializing MCP session..."
RESPONSE=$(curl -s -D headers.txt -X POST "$SERVER" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"curl-test","version":"1.0"}}}')

SESSION_ID=$(grep -i "Mcp-Session-Id" headers.txt | awk '{print $2}' | tr -d '\r\n ')
rm headers.txt

if [ -z "$SESSION_ID" ]; then
  echo "❌ Failed to get session ID"
  echo "Response: $RESPONSE"
  exit 1
fi

echo "✅ Session ID: $SESSION_ID"

# Step 2: Call the search tool
echo "🔍 Searching..."
curl -s -X POST "$SERVER" \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search","arguments":{"query":"latest AI news"}}}' | jq .

