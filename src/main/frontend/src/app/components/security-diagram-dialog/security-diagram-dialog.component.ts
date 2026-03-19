import { Component, signal, inject, DestroyRef, OnInit } from '@angular/core';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

const STEP_INTERVAL_MS = 1000;
const MAX_STEPS = 7;

@Component({
  selector: 'app-security-diagram-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule],
  templateUrl: './security-diagram-dialog.component.html',
  styleUrl: './security-diagram-dialog.component.scss',
})
export class SecurityDiagramDialogComponent implements OnInit {
  private readonly dialogRef = inject(MatDialogRef<SecurityDiagramDialogComponent>);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly visibleStep = signal(0);

  private timers: ReturnType<typeof setTimeout>[] = [];

  ngOnInit(): void {
    this.startAnimation();
    this.destroyRef.onDestroy(() => this.clearTimers());
  }

  replay(): void {
    this.clearTimers();
    this.visibleStep.set(0);
    setTimeout(() => this.startAnimation(), 50);
  }

  close(): void {
    this.dialogRef.close();
  }

  stepOpacity(step: number): number {
    return this.visibleStep() >= step ? 1 : 0;
  }

  lineDashOffset(step: number): number {
    return this.visibleStep() >= step ? 0 : 200;
  }

  checkFill(step: number): string {
    return this.visibleStep() >= step
      ? 'var(--mat-sys-tertiary-container)'
      : 'var(--mat-sys-surface-container-highest)';
  }

  checkStroke(step: number): string {
    return this.visibleStep() >= step
      ? 'var(--mat-sys-tertiary)'
      : 'var(--mat-sys-outline-variant)';
  }

  nodeStroke(step: number): string {
    return this.visibleStep() >= step
      ? 'var(--mat-sys-primary)'
      : 'var(--mat-sys-outline-variant)';
  }

  nodeStrokeWidth(step: number): number {
    return this.visibleStep() >= step ? 2 : 1.2;
  }

  private startAnimation(): void {
    for (let i = 1; i <= MAX_STEPS; i++) {
      const timer = setTimeout(() => this.visibleStep.set(i), i * STEP_INTERVAL_MS);
      this.timers.push(timer);
    }
  }

  private clearTimers(): void {
    this.timers.forEach(t => clearTimeout(t));
    this.timers = [];
  }
}
