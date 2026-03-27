import { Component, signal, effect, ViewChild, ElementRef, computed, OnInit } from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';

import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule, MatIconRegistry } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCardModule } from '@angular/material/card';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDividerModule } from '@angular/material/divider';
import { MarkdownComponent } from 'ngx-markdown';
import { ChatService, ChatMessage, HealthInfo } from '../../services/chat.service';
import { MemoryService, ConversationSummary, HistoryMessage } from '../../services/memory.service';
import { ActivityPanelComponent } from '../activity-panel/activity-panel.component';
import { ConfigPanelComponent } from '../config-panel/config-panel.component';

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatCardModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatDividerModule,
    MarkdownComponent,
    ActivityPanelComponent,
    ConfigPanelComponent
  ],
  templateUrl: './chat.component.html',
  styleUrl: './chat.component.scss'
})
export class ChatComponent implements OnInit {
  @ViewChild('messagesContainer') private messagesContainer!: ElementRef;
  @ViewChild('messageInput') private messageInput!: ElementRef<HTMLTextAreaElement>;
  
  protected messages = signal<ChatMessage[]>([]);
  protected userInput = signal('');
  protected isStreaming = signal(false);
  protected healthInfo = signal<HealthInfo | null>(null);
  protected sessionId = signal<string | null>(null);
  protected isCreatingSession = signal(false);
  protected activityPanelCollapsed = signal(false);
  protected configPanelCollapsed = signal(false);
  protected historyPanelCollapsed = signal(true);
  protected suggestedPrompts = signal<string[]>([]);
  protected selectedHistorySession = signal<string | null>(null);
  protected historyMessages = signal<HistoryMessage[]>([]);
  
  // Expose activities, todos, and memory from services
  protected activities = computed(() => this.chatService.activities());
  protected todos = computed(() => this.chatService.todos());
  protected conversations = computed(() => this.memoryService.conversations());
  protected memoryAvailable = computed(() => this.memoryService.memoryAvailable());

  private readonly SKILL_PROMPTS: Record<string, string> = {
    'gmail':            'Send an email via Gmail',
    'cf-space-auditor': 'Audit my Cloud Foundry spaces',
    'memory':           'What do you remember about me?',
  };

  private readonly MCP_PROMPTS: Record<string, string> = {
    'github-mcp-server': 'Show my open GitHub pull requests',
    'bosh':              'List all BOSH deployments',
    'tanzu-platform':    'Show my Tanzu Platform spaces',
    'factory':           'List Factory pipeline tasks',
  };

  constructor(
    private chatService: ChatService,
    private memoryService: MemoryService,
    private snackBar: MatSnackBar,
    private matIconRegistry: MatIconRegistry,
    private domSanitizer: DomSanitizer
  ) {
    // Register custom goose icon
    this.matIconRegistry.addSvgIcon(
      'goose',
      this.domSanitizer.bypassSecurityTrustResourceUrl('/goose-svgrepo-com.svg')
    );

    // Check Goose availability on init
    this.chatService.checkHealth().then(health => {
      this.healthInfo.set(health);
    });

    // Auto-scroll when messages update with smooth animation
    effect(() => {
      this.messages();
      // Use requestAnimationFrame for smoother scroll animation
      requestAnimationFrame(() => {
        setTimeout(() => this.scrollToBottom(), 50);
      });
    });

    // Automatically create a session on component init if Goose is available
    effect(() => {
      const health = this.healthInfo();
      if (health?.available && !this.sessionId() && !this.isCreatingSession()) {
        this.startNewConversation();
      }
    });
  }

  ngOnInit(): void {
    this.chatService.getConfig().then(config => {
      const prompts: string[] = ['What can you help me with?'];
      for (const skill of config.skills ?? []) {
        const p = this.SKILL_PROMPTS[skill.name];
        if (p) prompts.push(p);
      }
      for (const server of config.mcpServers ?? []) {
        const p = this.MCP_PROMPTS[server.name];
        if (p) prompts.push(p);
      }
      this.suggestedPrompts.set(prompts);
    });

    // Load conversation history (silently — memory profile may not be active)
    this.memoryService.loadConversations();
  }

  protected useSuggestion(prompt: string): void {
    this.userInput.set(prompt);
    this.sendMessage();
  }

  protected get gooseAvailable(): boolean {
    return this.healthInfo()?.available ?? false;
  }

  protected get gooseVersion(): string {
    return this.healthInfo()?.version ?? 'unknown';
  }

  protected get gooseProvider(): string {
    return this.healthInfo()?.provider ?? 'unknown';
  }

  protected get gooseModel(): string {
    return this.healthInfo()?.model ?? 'unknown';
  }

  protected get gooseMessage(): string {
    return this.healthInfo()?.message ?? '';
  }

  protected get gooseModelSource(): string {
    return this.healthInfo()?.source ?? 'unknown';
  }

  protected get isGenaiService(): boolean {
    return this.healthInfo()?.source === 'genai-service';
  }

  /**
   * Start a new conversation session
   */
  protected async startNewConversation(): Promise<void> {
    if (this.isCreatingSession()) {
      return;
    }

    this.isCreatingSession.set(true);

    try {
      // Close existing session if any
      const currentSessionId = this.sessionId();
      if (currentSessionId) {
        await this.chatService.closeSession(currentSessionId);
      }

      // Create new session
      const newSessionId = await this.chatService.createSession();
      this.sessionId.set(newSessionId);
      
      // Clear messages and todos for new conversation
      this.messages.set([]);
      this.chatService.clearTodos();
      this.chatService.clearActivities();
      
      this.snackBar.open('New Goose conversation started', 'Close', {
        duration: 2000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom'
      });
      
      console.log('Started new conversation with session:', newSessionId);
    } catch (error) {
      console.error('Failed to start new conversation:', error);
      this.snackBar.open('Failed to start conversation', 'Close', {
        duration: 3000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom'
      });
      this.sessionId.set(null);
    } finally {
      this.isCreatingSession.set(false);
    }
  }

  /**
   * End the current conversation
   */
  protected async copySessionId(): Promise<void> {
    const id = this.sessionId();
    if (!id) return;
    try {
      await navigator.clipboard.writeText(id);
      this.snackBar.open('Session ID copied to clipboard', 'Close', {
        duration: 2000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom'
      });
    } catch {
      this.snackBar.open('Failed to copy session ID', 'Close', {
        duration: 3000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom'
      });
    }
  }

  protected async endConversation(): Promise<void> {
    const currentSessionId = this.sessionId();
    if (!currentSessionId) {
      return;
    }

    try {
      await this.chatService.closeSession(currentSessionId);
      this.sessionId.set(null);
      this.messages.set([]);
      this.chatService.clearTodos();
      this.chatService.clearActivities();
      
      this.snackBar.open('Conversation ended', 'Close', {
        duration: 2000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom'
      });
    } catch (error) {
      console.error('Failed to end conversation:', error);
      this.snackBar.open('Failed to end conversation', 'Close', {
        duration: 3000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom'
      });
    }
  }

  protected toggleActivityPanel(): void {
    this.activityPanelCollapsed.update(v => !v);
  }

  protected toggleConfigPanel(): void {
    this.configPanelCollapsed.update(v => !v);
  }

  protected toggleHistoryPanel(): void {
    this.historyPanelCollapsed.update(v => !v);
    if (!this.historyPanelCollapsed()) {
      this.memoryService.loadConversations();
    }
  }

  protected async viewHistory(conversation: ConversationSummary): Promise<void> {
    this.selectedHistorySession.set(conversation.id);
    const msgs = await this.memoryService.getMessages(conversation.id);
    this.historyMessages.set(msgs);
  }

  protected clearHistoryView(): void {
    this.selectedHistorySession.set(null);
    this.historyMessages.set([]);
  }

  protected getRelativeTime(isoString: string): string {
    return this.memoryService.formatRelativeTime(isoString);
  }

  protected sendMessage(): void {
    const prompt = this.userInput().trim();
    const currentSessionId = this.sessionId();
    
    if (!prompt || this.isStreaming() || !currentSessionId) {
      return;
    }

    // Clear activities for new message
    this.chatService.clearActivities();
    
    // Auto-expand activity panel when streaming starts
    this.activityPanelCollapsed.set(false);

    // Add user message
    const userMessage: ChatMessage = {
      role: 'user',
      content: prompt,
      timestamp: new Date()
    };
    this.messages.update(msgs => [...msgs, userMessage]);
    this.userInput.set('');
    this.isStreaming.set(true);

    // Add assistant message placeholder
    const assistantMessage: ChatMessage = {
      role: 'assistant',
      content: '',
      timestamp: new Date(),
      streaming: true
    };
    this.messages.update(msgs => [...msgs, assistantMessage]);

    // Stream the response with token-level updates
    this.chatService.sendMessage(prompt, currentSessionId).subscribe({
      next: (token: string) => {
        // Append token directly - no newline needed for token-level streaming
        this.messages.update(msgs => {
          const lastMsg = msgs[msgs.length - 1];
          if (lastMsg.role === 'assistant') {
            return [
              ...msgs.slice(0, -1),
              {
                ...lastMsg,
                content: lastMsg.content + token
              }
            ];
          }
          return msgs;
        });
      },
      error: (error) => {
        console.error('Chat error:', error);
        
        const errorMessage = error.message || 'Failed to get response from Goose.';
        
        // Check if session expired
        if (errorMessage.includes('Session not found') || errorMessage.includes('expired')) {
          this.snackBar.open('Session expired. Starting new conversation...', 'Close', {
            duration: 3000,
            horizontalPosition: 'center',
            verticalPosition: 'bottom'
          });
          
          // Remove the failed assistant message
          this.messages.update(msgs => msgs.slice(0, -1));
          
          // Start new conversation and resend message
          this.startNewConversation().then(() => {
            // Resend the message after new session is created
            if (this.sessionId()) {
              this.userInput.set(prompt);
              setTimeout(() => this.sendMessage(), 500);
            }
          });
        } else {
          this.messages.update(msgs => {
            const lastMsg = msgs[msgs.length - 1];
            if (lastMsg.role === 'assistant' && lastMsg.streaming) {
              return [
                ...msgs.slice(0, -1),
                {
                  ...lastMsg,
                  content: lastMsg.content || `Error: ${errorMessage}`,
                  streaming: false
                }
              ];
            }
            return msgs;
          });
        }
        
        this.isStreaming.set(false);
        this.focusInput();
      },
      complete: () => {
        this.messages.update(msgs => {
          const lastMsg = msgs[msgs.length - 1];
          if (lastMsg.role === 'assistant') {
            return [
              ...msgs.slice(0, -1),
              {
                ...lastMsg,
                streaming: false
              }
            ];
          }
          return msgs;
        });
        this.isStreaming.set(false);
        this.focusInput();
      }
    });
  }

  protected onKeyPress(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.sendMessage();
    }
  }

  protected onInputChange(event: Event): void {
    const target = event.target as HTMLTextAreaElement;
    this.userInput.set(target.value);
  }

  private focusInput(): void {
    setTimeout(() => this.messageInput?.nativeElement?.focus(), 0);
  }

  private scrollToBottom(): void {
    if (this.messagesContainer) {
      const element = this.messagesContainer.nativeElement;
      // Smooth scroll to bottom
      element.scrollTo({
        top: element.scrollHeight,
        behavior: 'smooth'
      });
    }
  }
}

