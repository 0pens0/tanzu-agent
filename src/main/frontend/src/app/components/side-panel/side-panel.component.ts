import { Component, computed, effect, inject, input, signal, OnInit } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatTabsModule } from '@angular/material/tabs';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { ChatService, ActivityEvent, TodoItem, GooseConfig, SkillInfo, McpServerInfo } from '../../services/chat.service';
import { McpOAuthService } from '../../services/mcp-oauth.service';
import { MemoryService } from '../../services/memory.service';
import { ActivityPanelComponent } from '../activity-panel/activity-panel.component';
import { MemoryPanelComponent } from '../memory-panel/memory-panel.component';
import { SecurityDiagramDialogComponent } from '../security-diagram-dialog/security-diagram-dialog.component';

@Component({
  selector: 'app-side-panel',
  standalone: true,
  imports: [
    MatIconModule,
    MatButtonModule,
    MatTooltipModule,
    MatTabsModule,
    MatProgressSpinnerModule,
    MatDialogModule,
    ActivityPanelComponent,
    MemoryPanelComponent
  ],
  templateUrl: './side-panel.component.html',
  styleUrl: './side-panel.component.scss'
})
export class SidePanelComponent implements OnInit {
  readonly activities = input<ActivityEvent[]>([]);
  readonly todos      = input<TodoItem[]>([]);

  protected collapsed    = signal(false);
  protected selectedTab  = signal(0);

  // Config state (absorbed from ConfigPanelComponent)
  private readonly _config  = signal<GooseConfig | null>(null);
  private readonly _loading = signal(true);
  private readonly _error   = signal<string | null>(null);

  readonly config       = this._config.asReadonly();
  readonly loading      = this._loading.asReadonly();
  readonly error        = this._error.asReadonly();
  readonly skills       = computed(() => this._config()?.skills ?? []);
  readonly mcpServers   = computed(() => this._config()?.mcpServers ?? []);
  readonly hasSkills    = computed(() => this.skills().length > 0);
  readonly hasMcpServers = computed(() => this.mcpServers().length > 0);
  readonly isBrokerMode = computed(() => this.oauthService.isBrokerMode);

  // Activity tab
  readonly runningCount = computed(() =>
    this.activities().filter(a => a.status === 'running').length
  );

  // Memory tab
  readonly memoryAvailable = computed(() => this.memoryService.memoryAvailable());
  readonly contextLoaded   = computed(() => this.memoryService.contextLoaded());

  private readonly dialog = inject(MatDialog);

  constructor(
    private chatService: ChatService,
    private oauthService: McpOAuthService,
    private memoryService: MemoryService
  ) {
    // Auto-switch to Activity tab (index 2) when the agent starts running tools
    effect(() => {
      if (this.runningCount() > 0 && !this.collapsed()) {
        this.selectedTab.set(2);
      }
    });
  }

  ngOnInit(): void {
    this.loadConfig();
    this.oauthService.checkBrokerConfig();
  }

  private async loadConfig(): Promise<void> {
    this._loading.set(true);
    this._error.set(null);
    try {
      const config = await this.chatService.getConfig();
      this._config.set(config);
      if (config.error) this._error.set(config.error);
    } catch {
      this._error.set('Failed to load configuration');
    } finally {
      this._loading.set(false);
    }
  }

  protected toggle(): void {
    this.collapsed.update(v => !v);
  }

  protected onTabChange(index: number): void {
    this.selectedTab.set(index);
    // Refresh memory data when switching to the Memory tab (index 3)
    if (this.memoryAvailable() && index === 3) {
      this.memoryService.loadFacts();
      this.memoryService.loadConversations();
    }
  }

  // ── Skill helpers (from ConfigPanelComponent) ─────────────────────────

  protected getSkillIcon(skill: SkillInfo): string {
    switch (skill.source) {
      case 'git':  return 'cloud_download';
      case 'file': return 'folder';
      default:     return 'description';
    }
  }

  protected getSkillSourceLabel(skill: SkillInfo): string {
    switch (skill.source) {
      case 'git':  return 'Git';
      case 'file': return 'File';
      default:     return 'Inline';
    }
  }

  protected getSkillTooltip(skill: SkillInfo): string {
    if (skill.description) return skill.description;
    if (skill.repository) {
      return `${skill.repository}${skill.branch ? ` (${skill.branch})` : ''}${skill.path ? ` → ${skill.path}` : ''}`;
    }
    return skill.name;
  }

  protected getSkillRepoUrl(skill: SkillInfo): string | null {
    if (skill.source !== 'git' || !skill.repository) return null;
    const base   = skill.repository.replace(/\.git$/, '');
    const branch = skill.branch || 'main';
    return skill.path ? `${base}/tree/${branch}/${skill.path}` : base;
  }

  // ── MCP Server helpers (from ConfigPanelComponent) ────────────────────

  protected getMcpServerIcon(server: McpServerInfo): string {
    return server.type === 'streamable_http' ? 'cloud' : 'terminal';
  }

  protected getMcpServerTypeLabel(server: McpServerInfo): string {
    return server.type === 'streamable_http' ? 'HTTP' : 'Stdio';
  }

  protected getMcpServerTooltip(server: McpServerInfo): string {
    if (server.url)     return server.url;
    if (server.command) return `${server.command} ${server.args?.join(' ') ?? ''}`.trim();
    return server.name;
  }

  protected getMcpServerDetail(server: McpServerInfo): string {
    if (server.url)     return server.url;
    if (server.command) return server.command;
    return '';
  }

  protected requiresAuth(server: McpServerInfo): boolean {
    return server.requiresAuth === true;
  }

  protected openSecurityDiagram(): void {
    this.dialog.open(SecurityDiagramDialogComponent, {
      width: '880px',
      maxWidth: '95vw',
    });
  }

  protected openBrokerGrants(): void {
    this.oauthService.openBrokerGrants();
  }
}
